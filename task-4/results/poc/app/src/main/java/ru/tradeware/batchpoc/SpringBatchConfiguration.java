package ru.tradeware.batchpoc;

import java.util.Locale;

import javax.sql.DataSource;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

@Configuration
@EnableBatchProcessing
public class SpringBatchConfiguration {

    @Bean
    FlatFileItemReader<InventoryReportRow> inventoryReader(
            @Value("${app.input-file}") String inputFile
    ) {
        return new FlatFileItemReaderBuilder<InventoryReportRow>()
                .name("inventoryReportReader")
                .resource(new FileSystemResource(inputFile))
                .linesToSkip(1)
                .delimited()
                .names("sku", "warehouseCode", "quantity", "updatedAt")
                .targetType(InventoryReportRow.class)
                .build();
    }

    @Bean
    ItemProcessor<InventoryReportRow, InventoryRecord> inventoryProcessor(
            @Value("${app.input-file}") String inputFile
    ) {
        return row -> {
            if (row.getQuantity() < 0) {
                throw new IllegalArgumentException("Quantity must not be negative for sku=" + row.getSku());
            }

            InventoryRecord record = new InventoryRecord();
            record.setSku(row.getSku().trim().toUpperCase(Locale.ROOT));
            record.setWarehouseCode(row.getWarehouseCode().trim().toUpperCase(Locale.ROOT));
            record.setQuantity(row.getQuantity());
            record.setUpdatedAt(row.getUpdatedAt());
            record.setSourceFile(inputFile);
            return record;
        };
    }

    @Bean
    JdbcBatchItemWriter<InventoryRecord> inventoryWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<InventoryRecord>()
                .dataSource(dataSource)
                .sql(
                        "INSERT INTO inventory_balance "
                                + "(sku, warehouse_code, quantity, updated_at, source_file) "
                                + "VALUES (:sku, :warehouseCode, :quantity, CAST(:updatedAt AS timestamp), :sourceFile) "
                                + "ON CONFLICT (sku, warehouse_code) DO UPDATE SET "
                                + "quantity = EXCLUDED.quantity, "
                                + "updated_at = EXCLUDED.updated_at, "
                                + "source_file = EXCLUDED.source_file, "
                                + "loaded_at = now()"
                )
                .beanMapped()
                .build();
    }

    @Bean
    Step importInventoryStep(
            StepBuilderFactory stepBuilderFactory,
            FlatFileItemReader<InventoryReportRow> inventoryReader,
            ItemProcessor<InventoryReportRow, InventoryRecord> inventoryProcessor,
            JdbcBatchItemWriter<InventoryRecord> inventoryWriter,
            @Value("${app.batch.chunk-size}") int chunkSize
    ) {
        return stepBuilderFactory.get("importInventoryStep")
                .<InventoryReportRow, InventoryRecord>chunk(chunkSize)
                .reader(inventoryReader)
                .processor(inventoryProcessor)
                .writer(inventoryWriter)
                .build();
    }

    @Bean
    Job inventoryImportJob(JobBuilderFactory jobBuilderFactory, Step importInventoryStep) {
        return jobBuilderFactory.get("inventoryImportJob")
                .start(importInventoryStep)
                .build();
    }
}
