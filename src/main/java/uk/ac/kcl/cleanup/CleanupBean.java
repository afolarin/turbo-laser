package uk.ac.kcl.cleanup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobExecutionNotRunningException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.FixedBackOffPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import uk.ac.kcl.exception.BiolarkProcessingFailedException;
import uk.ac.kcl.exception.TurboLaserException;
import uk.ac.kcl.scheduling.ScheduledJobLauncher;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * Created by rich on 14/06/16.
 */
@Service
public class CleanupBean implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(CleanupBean.class);
    @Autowired
    JobOperator jobOperator;
    @Autowired
    JobExplorer jobExplorer;

    @Autowired(required = false)
    ScheduledJobLauncher scheduledJobLauncher;

    public void setJobExecutionId(long jobExecutionId) {
        this.jobExecutionId = jobExecutionId;
    }

    long jobExecutionId;

    boolean running;

    @Autowired
    private Environment env;


    private void cleanup(){
        LOG.info("stopping scheduler");
        if(scheduledJobLauncher!=null){
            scheduledJobLauncher.setContinueWork(false);
        }
        LOG.info("Attempting to stop running jobs");
        Set<Long> jobExecs = new HashSet<>();
        try {

            jobExecs.addAll(jobOperator.getRunningExecutions(env.getProperty("jobName")));
        } catch (NoSuchJobException e) {
            LOG.error("Couldn't get job list to stop executions ",e);
        } catch (NullPointerException ex){
            //probably no running jobs?
        }



        if(jobExecs.size() == 0) {
            LOG.info("No running jobs detected. Exiting now");
            return;
        }else if(jobExecs.size() > 1){
            LOG.warn("Detected more than one "+env.getProperty("jobName")+ " with status of running.");
        };


        int stoppedCount = 0;
        for(Long l : jobExecs){
            try{
                jobOperator.stop(l);
            } catch (NoSuchJobExecutionException e) {
                LOG.error("Job no longer exists ",e);
                stoppedCount++;
            }catch(JobExecutionNotRunningException e){
                LOG.info("Job is no longer running ",e);
                stoppedCount++;
            }
        }

        if(stoppedCount == jobExecs.size()){
            LOG.info("Jobs successfully stopped, completed or are known to have failed");
            return;
        }else{
            RetryTemplate retryTemplate = getRetryTemplate();
            try {
                stoppedCount = stoppedCount + retryTemplate.execute(new RetryCallback<Integer,TurboLaserException>() {
                    public Integer doWithRetry(RetryContext context) {
                        // business logic here
                        for(Long l : jobExecs){
                            JobExecution exec = jobExplorer.getJobExecution(l);
                            BatchStatus status = exec.getStatus();
                            LOG.info("Job name "+ exec.getJobInstance().getJobName() +" has status of "+ status );
                            if (status == BatchStatus.STOPPED ||
                                    status == BatchStatus.FAILED ||
                                    status == BatchStatus.COMPLETED ||
                                    status == BatchStatus.ABANDONED ) {
                                context.setAttribute("stopped_jobs",(
                                        Integer.valueOf(context.getAttribute("stopped_jobs").toString())+1));
                            }
                        }

                        return Integer.valueOf(context.getAttribute("stopped_jobs").toString());
                    }
                }, new RecoveryCallback() {
                    @Override
                    public Object recover(RetryContext context) throws TurboLaserException {

                        return context;
                    }
                });
            } catch (TurboLaserException e) {
                LOG.warn("Unable to gracefully stop jobs. Job Repository may be in unknown state");
            }
            if(stoppedCount == jobExecs.size()){
                LOG.info("Jobs successfully stopped, completed or are known to have failed");
                return;
            }
        }
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        LOG.info("****************SHUTDOWN INITIATED*********************");
        cleanup();
        stop();
        callback.run();
    }

    @Override
    public void start() {
        LOG.info("****************STARTUP INITIATED*********************");
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    public RetryTemplate getRetryTemplate(){
        TimeoutRetryPolicy retryPolicy = new TimeoutRetryPolicy();
        retryPolicy.setTimeout(Long.valueOf(env.getProperty("shutdownTimeout")));
        FixedBackOffPolicy backOffPolicy = new FixedBackOffPolicy();
        backOffPolicy.setBackOffPeriod(5);

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOffPolicy);
        return template;
    }

}
