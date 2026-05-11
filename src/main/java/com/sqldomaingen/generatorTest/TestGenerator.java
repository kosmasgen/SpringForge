package com.sqldomaingen.generatorTest;

import com.sqldomaingen.model.Entity;
import com.sqldomaingen.model.Table;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

import java.util.List;
import java.util.Objects;

/**
 * Coordinates test generation for controller, service implementation,
 * mapper, entity POJO, and DTO POJO tests.
 */
@Log4j2
@RequiredArgsConstructor
public class TestGenerator {

    private final ControllerTestGenerator controllerTestGenerator;
    private final ServiceImplTestGenerator serviceImplTestGenerator;
    private final MapperTestGenerator mapperTestGenerator;
    private final EntityPojoTestGenerator entityPojoTestGenerator;
    private final DtoPojoTestGenerator dtoPojoTestGenerator;

    /**
     * Creates a test generator with default sub-generators.
     */
    public TestGenerator() {
        this(
                new ControllerTestGenerator(),
                new ServiceImplTestGenerator(),
                new MapperTestGenerator(),
                new EntityPojoTestGenerator(),
                new DtoPojoTestGenerator()
        );
    }


    /**
     * Main entry point used by the generation pipeline.
     *
     * @param businessTables tables eligible for CRUD generation
     * @param allJavaTables all Java generation tables
     * @param entities generated entity metadata
     * @param outputDir project root output directory
     * @param basePackage base Java package
     * @param overwrite overwrite existing files when true
     */
    public void generateTests(
            List<Table> businessTables,
            List<Table> allJavaTables,
            List<Entity> entities,
            String outputDir,
            String basePackage,
            boolean overwrite
    ) {
        Objects.requireNonNull(businessTables, "businessTables must not be null");
        Objects.requireNonNull(allJavaTables, "allJavaTables must not be null");
        Objects.requireNonNull(entities, "entities must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        controllerTestGenerator.generateControllerTests(
                businessTables,
                outputDir,
                basePackage,
                overwrite
        );

        serviceImplTestGenerator.generateServiceImplTests(
                businessTables,
                entities,
                outputDir,
                basePackage,
                overwrite
        );

        mapperTestGenerator.generateMapperTests(
                allJavaTables,
                entities,
                outputDir,
                basePackage,
                overwrite
        );

        entityPojoTestGenerator.generateEntityPojoTests(
                entities,
                outputDir,
                basePackage,
                overwrite
        );

        dtoPojoTestGenerator.generateDtoPojoTests(
                entities,
                outputDir,
                basePackage,
                overwrite
        );

        log.info("Test classes generated successfully.");
    }

    /**
     * Backwards-compatible overload.
     *
     * @param businessTables tables eligible for CRUD generation
     * @param allJavaTables all Java generation tables
     * @param entities generated entity metadata
     * @param outputDir project root output directory
     * @param basePackage base Java package
     */
    public void generateAllTests(
            List<Table> businessTables,
            List<Table> allJavaTables,
            List<Entity> entities,
            String outputDir,
            String basePackage
    ) {
        generateTests(
                businessTables,
                allJavaTables,
                entities,
                outputDir,
                basePackage,
                true
        );
    }
}