package src

import com.mongodb.MongoClient
import com.mongodb.MongoClientOptions
import com.mongodb.ReadConcern
import com.mongodb.ReadPreference
import com.mongodb.WriteConcern
import org.springframework.boot.autoconfigure.mongo.MongoClientFactory
import org.springframework.boot.autoconfigure.mongo.MongoProperties
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.convert.converter.Converter
import org.springframework.core.env.PropertiesPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.data.mapping.model.SimpleTypeHolder
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoDbFactory
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import org.springframework.data.mongodb.core.mapping.MongoSimpleTypes
import org.springframework.data.mongodb.core.query.Query
import uk.ac.ebi.eva.accession.core.service.nonhuman.ClusteredVariantAccessioningService
import uk.ac.ebi.eva.accession.core.service.nonhuman.SubmittedVariantAccessioningService

import java.time.LocalDateTime
import java.time.ZoneId

import static org.springframework.data.mongodb.core.query.Query.query;
import static org.springframework.data.mongodb.core.query.Criteria.where;

// This class is to create an environment for EVA databases from a given properties file
// We do this instead of Spring Boot autowiring because Spring boot
// doesn't support multiple application contexts to hold properties
// for multiple environments (ex: prod and dev) at the same time
// Most of the code below is borrowed from MongoConfiguration in the eva-accession repository
public class EVADatabaseEnvironment {
    MongoClient mongoClient
    MongoTemplate mongoTemplate
    SubmittedVariantAccessioningService submittedVariantAccessioningService
    ClusteredVariantAccessioningService clusteredVariantAccessioningService
    AnnotationConfigApplicationContext springApplicationContext

    EVADatabaseEnvironment(MongoClient mongoClient, MongoTemplate mongoTemplate,
                           SubmittedVariantAccessioningService submittedVariantAccessioningService,
                           ClusteredVariantAccessioningService clusteredVariantAccessioningService,
                           AnnotationConfigApplicationContext springApplicationContext) {
        this.mongoClient = mongoClient
        this.mongoTemplate = mongoTemplate
        this.submittedVariantAccessioningService = submittedVariantAccessioningService
        this.clusteredVariantAccessioningService = clusteredVariantAccessioningService
        this.springApplicationContext = springApplicationContext
    }

    EVADatabaseEnvironment(MongoClient mongoClient, MongoTemplate mongoTemplate) {
        this.mongoClient = mongoClient
        this.mongoTemplate = mongoTemplate
    }

    public final <T3> void bulkUpsert(List<T3> docsToInsert, Class<T3> documentClass) {
        if (docsToInsert.size() > 0) {
            def docsToInsertGrouped = docsToInsert.groupBy { it.getId() }
            def docsExisting = this.mongoTemplate.find(query(where("_id").in(docsToInsertGrouped.keySet())),
                    documentClass).collect { it.getId() }.toSet()
            docsToInsertGrouped.removeAll { k, v -> docsExisting.contains(k) }
            this.mongoTemplate.insert(docsToInsertGrouped.values().flatten(), documentClass)
        }
    }

    static EVADatabaseEnvironment createFromSpringContext(String propertiesFile, Class springApplicationClass) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()
        def appProps = new Properties()
        appProps.load(new FileInputStream(new File(propertiesFile)))
        context.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource("main", appProps))
        context.register(springApplicationClass)
        context.refresh()

        def mc = context.getBean(MongoClient.class)
        def mt = context.getBean(MongoTemplate.class)
        def sva = context.getBean(SubmittedVariantAccessioningService.class)
        def cva = context.getBean(ClusteredVariantAccessioningService.class)
        return new EVADatabaseEnvironment(mc, mt, sva, cva, context)
    }

    static EVADatabaseEnvironment parseFrom(String propertiesFile) {
        def properties = new Properties()
        properties.load(new FileInputStream(new File(propertiesFile)))
        MongoProperties mongoProperties = getMongoPropertiesForEnv(properties)
        MongoClient mongoClient = getMongoClientForEnv(properties, mongoProperties)
        MongoTemplate mongoTemplate = getMongoTemplateForEnv(mongoClient, mongoProperties)

        return new EVADatabaseEnvironment(mongoClient, mongoTemplate)
    }

    private static MongoClient getMongoClientForEnv(Properties properties, MongoProperties mongoProperties) {
        def readPreference = ReadPreference.valueOf(properties.getProperty("mongodb.read-preference"))
        def mongoClientOptions = new MongoClientOptions.Builder().readPreference(readPreference).writeConcern(WriteConcern.MAJORITY).readConcern(ReadConcern.MAJORITY)

        def environment = new StandardEnvironment()
        def mongoClient = new MongoClientFactory(mongoProperties, environment).createMongoClient(mongoClientOptions.build())
        mongoClient
    }

    private static MongoProperties getMongoPropertiesForEnv(Properties properties) {
        def mongoProperties = new MongoProperties()
        mongoProperties.setDatabase(properties.getProperty("spring.data.mongodb.database"))
        mongoProperties.setHost(properties.getProperty("spring.data.mongodb.host"))
        mongoProperties.setPort(Integer.parseInt(properties.getProperty("spring.data.mongodb.port")))
        mongoProperties.setUsername(properties.getProperty("spring.data.mongodb.username"))
        mongoProperties.setPassword(properties.getProperty("spring.data.mongodb.password").toCharArray())
        mongoProperties.setAuthenticationDatabase(properties.getProperty("spring.data.mongodb.authentication-database"))
        return mongoProperties
    }

    private static MongoTemplate getMongoTemplateForEnv(MongoClient mongoClient, MongoProperties mongoProperties) {
        def mongoDbFactory = new SimpleMongoDbFactory(mongoClient, mongoProperties.getDatabase())
        def mappingContext = new MongoMappingContext()
        SimpleTypeHolder simpleTypeHolder = new SimpleTypeHolder(new HashSet<>(Arrays.asList(
                Date.class,
                LocalDateTime.class
        )), MongoSimpleTypes.HOLDER)
        mappingContext.setSimpleTypeHolder(simpleTypeHolder)
        def mappingMongoConverter = new MappingMongoConverter(new DefaultDbRefResolver(mongoDbFactory), mappingContext)
        mappingMongoConverter.setTypeMapper(new DefaultMongoTypeMapper(null))
        List<Converter<?, ?>> converterList = new ArrayList<Converter<?, ?>>()
        converterList.add(new MongoLocalDateTimeFromStringConverter())
        converterList.add(new MongoDateTimeFromStringConverter())
        mappingMongoConverter.setMapKeyDotReplacement("#")
        mappingMongoConverter.afterPropertiesSet()
        return new MongoTemplate(mongoDbFactory, mappingMongoConverter)
    }

    private static final class MongoLocalDateTimeFromStringConverter implements Converter<String, LocalDateTime> {
        @Override
        LocalDateTime convert(String source) {
            return source == null ? null : LocalDateTime.parse(source)
        }
    }

    private static final class MongoDateTimeFromStringConverter implements Converter<String, Date> {
        @Override
        Date convert(String source) {
            return source == null ? null :
                    Date.from(LocalDateTime.parse(source).toLocalDate()
                            .atStartOfDay(ZoneId.systemDefault()).toInstant())
        }
    }
}
