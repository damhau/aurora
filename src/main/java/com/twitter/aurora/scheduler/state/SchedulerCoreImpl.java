/*
 * Copyright 2013 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.aurora.scheduler.state;

import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.inject.Inject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import com.twitter.aurora.gen.ScheduleStatus;
import com.twitter.aurora.gen.ShardUpdateResult;
import com.twitter.aurora.gen.UpdateResult;
import com.twitter.aurora.scheduler.TaskIdGenerator;
import com.twitter.aurora.scheduler.base.JobKeys;
import com.twitter.aurora.scheduler.base.Query;
import com.twitter.aurora.scheduler.base.ScheduleException;
import com.twitter.aurora.scheduler.base.Tasks;
import com.twitter.aurora.scheduler.configuration.ConfigurationManager.TaskDescriptionException;
import com.twitter.aurora.scheduler.configuration.ParsedConfiguration;
import com.twitter.aurora.scheduler.state.StateManagerImpl.UpdateException;
import com.twitter.aurora.scheduler.storage.Storage;
import com.twitter.aurora.scheduler.storage.Storage.MutableStoreProvider;
import com.twitter.aurora.scheduler.storage.Storage.MutateWork;
import com.twitter.aurora.scheduler.storage.entities.IAssignedTask;
import com.twitter.aurora.scheduler.storage.entities.IJobConfiguration;
import com.twitter.aurora.scheduler.storage.entities.IJobKey;
import com.twitter.aurora.scheduler.storage.entities.IScheduledTask;
import com.twitter.aurora.scheduler.storage.entities.ITaskConfig;
import com.twitter.common.args.Arg;
import com.twitter.common.args.CmdLine;
import com.twitter.common.args.constraints.Positive;

import static com.google.common.base.Preconditions.checkNotNull;

import static com.twitter.aurora.gen.ScheduleStatus.KILLING;
import static com.twitter.aurora.gen.ScheduleStatus.RESTARTING;
import static com.twitter.aurora.gen.ScheduleStatus.ROLLBACK;
import static com.twitter.aurora.gen.ScheduleStatus.UPDATING;
import static com.twitter.aurora.scheduler.base.Tasks.ACTIVE_STATES;

/**
 * Implementation of the scheduler core.
 */
class SchedulerCoreImpl implements SchedulerCore {
  @Positive
  @CmdLine(name = "max_tasks_per_job", help = "Maximum number of allowed tasks in a single job.")
  public static final Arg<Integer> MAX_TASKS_PER_JOB = Arg.create(1000);

  private static final Logger LOG = Logger.getLogger(SchedulerCoreImpl.class.getName());

  private static final Predicate<IScheduledTask> IS_UPDATING = new Predicate<IScheduledTask>() {
    @Override public boolean apply(IScheduledTask task) {
      return task.getStatus() == UPDATING || task.getStatus() == ROLLBACK;
    }
  };

  private final Storage storage;

  private final CronJobManager cronScheduler;

  // Schedulers that are responsible for triggering execution of jobs.
  private final ImmutableList<JobManager> jobManagers;

  // TODO(Bill Farner): Avoid using StateManagerImpl.
  // State manager handles persistence of task modifications and state transitions.
  private final StateManagerImpl stateManager;
  private final TaskIdGenerator taskIdGenerator;
  private final JobFilter jobFilter;

  /**
   * Creates a new core scheduler.
   *
   * @param storage Backing store implementation.
   * @param cronScheduler Cron scheduler.
   * @param immediateScheduler Immediate scheduler.
   * @param stateManager Persistent state manager.
   * @param taskIdGenerator Task ID generator.
   * @param jobFilter Job filter.
   */
  @Inject
  public SchedulerCoreImpl(
      Storage storage,
      CronJobManager cronScheduler,
      ImmediateJobManager immediateScheduler,
      StateManagerImpl stateManager,
      TaskIdGenerator taskIdGenerator,
      JobFilter jobFilter) {

    this.storage = checkNotNull(storage);

    // The immediate scheduler will accept any job, so it's important that other schedulers are
    // placed first.
    this.jobManagers = ImmutableList.of(cronScheduler, immediateScheduler);
    this.cronScheduler = cronScheduler;
    this.stateManager = checkNotNull(stateManager);
    this.taskIdGenerator = checkNotNull(taskIdGenerator);
    this.jobFilter = checkNotNull(jobFilter);
  }

  private boolean hasActiveJob(IJobConfiguration job) {
    return Iterables.any(jobManagers, managerHasJob(job));
  }

  @Override
  public synchronized void tasksDeleted(Set<String> taskIds) {
    setTaskStatus(Query.taskScoped(taskIds), ScheduleStatus.UNKNOWN, Optional.<String>absent());
  }

  @Override
  public synchronized void createJob(ParsedConfiguration parsedConfiguration)
      throws ScheduleException {

    IJobConfiguration job = parsedConfiguration.getJobConfig();
    if (hasActiveJob(job)) {
      throw new ScheduleException("Job already exists: " + JobKeys.toPath(job));
    }

    runJobFilters(job.getKey(), job.getTaskConfig(), job.getInstanceCount(), false);

    boolean accepted = false;
    for (final JobManager manager : jobManagers) {
      if (manager.receiveJob(parsedConfiguration)) {
        LOG.info("Job accepted by manager: " + manager.getUniqueKey());
        accepted = true;
        break;
      }
    }

    if (!accepted) {
      LOG.severe("Job was not accepted by any of the configured schedulers, discarding.");
      LOG.severe("Discarded job: " + job);
      throw new ScheduleException("Job not accepted, discarding.");
    }
  }

  // This number is derived from the maximum file name length limit on most UNIX systems, less
  // the number of characters we've observed being added by mesos for the executor ID, prefix, and
  // delimiters.
  @VisibleForTesting
  static final int MAX_TASK_ID_LENGTH = 255 - 90;

  // TODO(maximk): Consider a better approach to quota checking. MESOS-4476.
  private void runJobFilters(IJobKey jobKey, ITaskConfig task, int count, boolean incremental)
      throws ScheduleException {

    int instanceCount = count;
    if (incremental) {
      instanceCount +=
          Storage.Util.weaklyConsistentFetchTasks(storage, Query.jobScoped(jobKey).active()).size();
    }

    // TODO(maximk): This is a short-term hack to stop the bleeding from
    //               https://issues.apache.org/jira/browse/MESOS-691
    if (taskIdGenerator.generate(task, instanceCount).length() > MAX_TASK_ID_LENGTH) {
      throw new ScheduleException(
          "Task ID is too long, please shorten your role or job name.");
    }

    JobFilter.JobFilterResult filterResult = jobFilter.filter(task, instanceCount);
    // TODO(maximk): Consider deprecating JobFilterResult in favor of custom exception.
    if (!filterResult.isPass()) {
      throw new ScheduleException(filterResult.getReason());
    }

    if (instanceCount > MAX_TASKS_PER_JOB.get()) {
      throw new ScheduleException("Job exceeds task limit of " + MAX_TASKS_PER_JOB.get());
    }
  }

  @Override
  public void validateJobResources(ParsedConfiguration parsedConfiguration)
      throws ScheduleException {

    IJobConfiguration job = parsedConfiguration.getJobConfig();
    runJobFilters(job.getKey(), job.getTaskConfig(), job.getInstanceCount(), false);
  }

  @Override
  public void addInstances(
      final IJobKey jobKey,
      final ImmutableSet<Integer> instanceIds,
      final ITaskConfig config) throws ScheduleException {

    runJobFilters(jobKey, config, instanceIds.size(), true);
    storage.write(new MutateWork.NoResult<ScheduleException>() {
      @Override
      protected void execute(MutableStoreProvider storeProvider)
          throws ScheduleException {

        ImmutableSet<IScheduledTask> tasks =
            storeProvider.getTaskStore().fetchTasks(Query.jobScoped(jobKey).active());

        Set<Integer> existingInstanceIds =
            FluentIterable.from(tasks).transform(Tasks.SCHEDULED_TO_INSTANCE_ID).toSet();
        if (!Sets.intersection(existingInstanceIds, instanceIds).isEmpty()) {
          throw new ScheduleException("Instance ID collision detected.");
        }

        stateManager.insertPendingTasks(Maps.asMap(instanceIds, Functions.constant(config)));
      }
    });
  }

  @Override
  public synchronized void startCronJob(IJobKey jobKey)
      throws ScheduleException, TaskDescriptionException {

    checkNotNull(jobKey);

    if (!cronScheduler.hasJob(jobKey)) {
      throw new ScheduleException("Cron job does not exist for " + JobKeys.toPath(jobKey));
    }

    cronScheduler.startJobNow(jobKey);
  }

  /**
   * Creates a predicate that will determine whether a job manager has a job matching a job key.
   *
   * @param job Job to match.
   * @return A new predicate matching the job owner and name given.
   */
  private static Predicate<JobManager> managerHasJob(final IJobConfiguration job) {
    return new Predicate<JobManager>() {
      @Override public boolean apply(JobManager manager) {
        return manager.hasJob(job.getKey());
      }
    };
  }

  @Override
  public synchronized void setTaskStatus(
      Query.Builder query,
      final ScheduleStatus status,
      Optional<String> message) {

    checkNotNull(query);
    checkNotNull(status);

    stateManager.changeState(query, status, message);
  }

  @Override
  public synchronized void killTasks(Query.Builder query, String user) throws ScheduleException {
    checkNotNull(query);
    LOG.info("Killing tasks matching " + query);

    boolean jobDeleted = false;
    boolean updateFinished = false;

    if (Query.isOnlyJobScoped(query)) {
      // If this looks like a query for all tasks in a job, instruct the scheduler modules to
      // delete the job.
      IJobKey jobKey = JobKeys.from(query).get();
      for (JobManager manager : jobManagers) {
        if (manager.deleteJob(jobKey)) {
          jobDeleted = true;
        }
      }

      if (!jobDeleted) {
        try {
          updateFinished = stateManager.finishUpdate(
              jobKey,
              user,
              Optional.<String>absent(),
              UpdateResult.TERMINATE,
              false);
        } catch (UpdateException e) {
          LOG.severe(
              String.format("Could not terminate job update for %s\n%s", query, e.getMessage()));
        }
      }
    }

    // Unless statuses were specifically supplied, only attempt to kill active tasks.
    Query.Builder taskQuery = query.get().isSetStatuses() ? query.byStatus(ACTIVE_STATES) : query;

    int tasksAffected =
        stateManager.changeState(taskQuery, KILLING, Optional.of("Killed by " + user));
    if (!jobDeleted && !updateFinished && (tasksAffected == 0)) {
      throw new ScheduleException("No jobs to kill");
    }
  }

  @Override
  public void restartShards(
      IJobKey jobKey,
      final Set<Integer> shards,
      final String requestingUser) throws ScheduleException {

    if (!JobKeys.isValid(jobKey)) {
      throw new ScheduleException("Invalid job key: " + jobKey);
    }

    if (shards.isEmpty()) {
      throw new ScheduleException("At least one shard must be specified.");
    }

    final Query.Builder query = Query.instanceScoped(jobKey, shards).active();
    storage.write(new MutateWork.NoResult<ScheduleException>() {
      @Override protected void execute(MutableStoreProvider storeProvider)
          throws ScheduleException {

        Set<IScheduledTask> matchingTasks = storeProvider.getTaskStore().fetchTasks(query);
        if (matchingTasks.size() != shards.size()) {
          throw new ScheduleException("Not all requested shards are active.");
        }
        LOG.info("Restarting shards matching " + query);
        stateManager.changeState(
            Query.taskScoped(Tasks.ids(matchingTasks)),
            RESTARTING,
            Optional.of("Restarted by " + requestingUser));
      }
    });
  }

  @Override
  public synchronized Optional<String> initiateJobUpdate(
      final ParsedConfiguration parsedConfiguration) throws ScheduleException {

    final IJobConfiguration job = parsedConfiguration.getJobConfig();
    final IJobKey jobKey = job.getKey();
    if (cronScheduler.hasJob(jobKey)) {
      cronScheduler.updateJob(parsedConfiguration);
      return Optional.absent();
    }

    return storage.write(new MutateWork<Optional<String>, ScheduleException>() {
      @Override public Optional<String> apply(MutableStoreProvider storeProvider)
          throws ScheduleException {
        Query.Builder query = Query.jobScoped(jobKey).active();
        Set<IScheduledTask> existingTasks = storeProvider.getTaskStore().fetchTasks(query);

        // Reject if any existing task for the job is in UPDATING/ROLLBACK
        if (Iterables.any(existingTasks, IS_UPDATING)) {
          throw new ScheduleException("Update/Rollback already in progress for "
              + JobKeys.toPath(job));
        }

        runJobFilters(jobKey, job.getTaskConfig(), job.getInstanceCount(), false);

        try {
          return Optional.of(
              stateManager.registerUpdate(jobKey, parsedConfiguration.getTaskConfigs()));
        } catch (UpdateException e) {
          LOG.log(Level.INFO, "Failed to start update.", e);
          throw new ScheduleException(e.getMessage(), e);
        }
      }
    });
  }

  @Override
  public synchronized Map<Integer, ShardUpdateResult> updateShards(
      IJobKey jobKey,
      String invokingUser,
      Set<Integer> shards,
      String updateToken) throws ScheduleException {

    try {
      return stateManager.modifyShards(jobKey, invokingUser, shards, updateToken, true);
    } catch (UpdateException e) {
      LOG.log(Level.INFO, "Failed to update shards for " + JobKeys.toPath(jobKey), e);
      throw new ScheduleException(e.getMessage(), e);
    }
  }

  @Override
  public synchronized Map<Integer, ShardUpdateResult> rollbackShards(
      IJobKey jobKey,
      String invokingUser,
      Set<Integer> shards,
      String updateToken) throws ScheduleException {

    try {
      return stateManager.modifyShards(jobKey, invokingUser, shards, updateToken, false);
    } catch (UpdateException e) {
      LOG.log(Level.INFO, "Failed to roll back shards for " + JobKeys.toPath(jobKey), e);
      throw new ScheduleException(e.getMessage(), e);
    }
  }

  @Override
  public synchronized void finishUpdate(
      IJobKey jobKey,
      String invokingUser,
      Optional<String> updateToken,
      UpdateResult result) throws ScheduleException {

    try {
      stateManager.finishUpdate(jobKey, invokingUser, updateToken, result, true);
    } catch (UpdateException e) {
      LOG.log(Level.INFO, "Failed to finish update for " + JobKeys.toPath(jobKey), e);
      throw new ScheduleException(e.getMessage(), e);
    }
  }

  @Override
  public synchronized void preemptTask(IAssignedTask task, IAssignedTask preemptingTask) {
    checkNotNull(task);
    checkNotNull(preemptingTask);
    // TODO(William Farner): Throw SchedulingException if either task doesn't exist, etc.

    stateManager.changeState(Query.taskScoped(task.getTaskId()), ScheduleStatus.PREEMPTING,
        Optional.of("Preempting in favor of " + preemptingTask.getTaskId()));
  }
}
