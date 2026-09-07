package ru.tradeware.batchpoc;

import java.util.Comparator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class BatchPocApplication {
    private static final Logger log = LoggerFactory.getLogger(BatchPocApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(BatchPocApplication.class, args);
    }

    @Bean
    CommandLineRunner runInventoryImport(
            JobLauncher jobLauncher,
            Job inventoryImportJob,
            ConfigurableApplicationContext context,
            @Value("${app.input-file}") String inputFile
    ) {
        return args -> {
            JobParameters parameters = new JobParametersBuilder()
                    .addString("inputFile", inputFile)
                    .addLong("run.id", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(inventoryImportJob, parameters);
            StepExecution step = execution.getStepExecutions().stream()
                    .max(Comparator.comparing(StepExecution::getStartTime))
                    .orElseThrow(() -> new IllegalStateException("StepExecution was not created"));

            log.info(
                    "{\"event\":\"spring_batch_job_finished\",\"job\":\"{}\",\"status\":\"{}\","
                            + "\"read_count\":{},\"write_count\":{},\"commit_count\":{},\"skip_count\":{},"
                            + "\"job_execution_id\":{},\"step_execution_id\":{}}",
                    execution.getJobInstance().getJobName(),
                    execution.getStatus(),
                    step.getReadCount(),
                    step.getWriteCount(),
                    step.getCommitCount(),
                    step.getSkipCount(),
                    execution.getId(),
                    step.getId()
            );

            int exitCode = SpringApplication.exit(
                    context,
                    () -> execution.getStatus() == BatchStatus.COMPLETED ? 0 : 1
            );
            System.exit(exitCode);
        };
    }
}
