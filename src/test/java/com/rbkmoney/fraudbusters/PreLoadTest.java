package com.rbkmoney.fraudbusters;

import com.rbkmoney.damsel.domain.RiskScore;
import com.rbkmoney.damsel.fraudbusters.Command;
import com.rbkmoney.damsel.fraudbusters.CommandBody;
import com.rbkmoney.damsel.fraudbusters.Template;
import com.rbkmoney.damsel.proxy_inspector.Context;
import com.rbkmoney.damsel.proxy_inspector.InspectorProxySrv;
import com.rbkmoney.fraudbusters.constant.ResultStatus;
import com.rbkmoney.fraudbusters.domain.MgEventSinkRow;
import com.rbkmoney.fraudbusters.repository.EventRepository;
import com.rbkmoney.fraudbusters.serde.CommandDeserializer;
import com.rbkmoney.fraudbusters.serde.MgEventSinkRowDeserializer;
import com.rbkmoney.fraudbusters.stream.aggregate.EventSinkAggregationStreamFactoryImpl;
import com.rbkmoney.fraudbusters.util.BeanUtil;
import com.rbkmoney.machinegun.eventsink.MachineEvent;
import com.rbkmoney.machinegun.eventsink.SinkEvent;
import com.rbkmoney.woody.thrift.impl.http.THClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.thrift.TException;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import ru.yandex.clickhouse.ClickHouseDataSource;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;


@Slf4j
@RunWith(SpringRunner.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = RANDOM_PORT, classes = FraudBustersApplication.class)
@ContextConfiguration(initializers = PreLoadTest.Initializer.class)
public class PreLoadTest extends KafkaAbstractTest {

    private static final String TEMPLATE = "rule: 12 >= 1\n" +
            " -> accept;";
    private static final String TEST = "test";

    private InspectorProxySrv.Iface client;

    @MockBean
    ClickHouseDataSource clickHouseDataSource;

    @MockBean
    JdbcTemplate jdbcTemplate;

    @MockBean
    EventRepository eventRepository;

    @Autowired
    EventSinkAggregationStreamFactoryImpl eventSinkAggregationStreamFactory;

    @Autowired
    Properties eventSinkStreamProperties;

    @LocalServerPort
    int serverPort;

    @Value("${kafka.state.dir}")
    private String stateDir;

    private static String SERVICE_URL = "http://localhost:%s/fraud_inspector/v1";

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext configurableApplicationContext) {
            try {
                createTemplate();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }

        private static void createTemplate() throws InterruptedException, ExecutionException {

            try (Producer<String, Command> producer = createProducer()) {
                Command command = new Command();
                Template template = new Template();
                String id = TEST;
                template.setId(id);
                template.setTemplate(PreLoadTest.TEMPLATE.getBytes());
                command.setCommandBody(CommandBody.template(template));
                command.setCommandType(com.rbkmoney.damsel.fraudbusters.CommandType.CREATE);
                ProducerRecord<String, Command> producerRecord = new ProducerRecord<>("template",
                        id, command);
                producer.send(producerRecord).get();
            }

            try (Consumer<String, Object> consumer = createConsumer(CommandDeserializer.class)) {
                consumer.subscribe(List.of("template"));
                Unreliables.retryUntilTrue(10, TimeUnit.SECONDS, () -> {
                    ConsumerRecords<String, Object> records = consumer.poll(Duration.ofSeconds(1L));
                    return !records.isEmpty();
                });
            }
        }
    }

    @Before
    public void init() throws ExecutionException, InterruptedException {
        produceReferenceWithWait(true, null, null, TEST, 10);
    }

    @Test
    public void inspectPaymentTest() throws URISyntaxException, TException, ExecutionException, InterruptedException {
        THClientBuilder clientBuilder = new THClientBuilder()
                .withAddress(new URI(String.format(SERVICE_URL, serverPort)))
                .withNetworkTimeout(300000);
        client = clientBuilder.build(InspectorProxySrv.Iface.class);

        Context context = BeanUtil.createContext();
        RiskScore riskScore = client.inspectPayment(context);

        Assert.assertEquals(RiskScore.low, riskScore);
    }

    @Test
    public void aggregateStreamTest() throws ExecutionException, InterruptedException {
        produceMessageToEventSink(BeanUtil.createMessageCreateInvoice(BeanUtil.SOURCE_ID));
        produceMessageToEventSink(BeanUtil.createMessageCreateInvoice(BeanUtil.SOURCE_ID + "_1"));
        produceMessageToEventSink(BeanUtil.createMessageCreateInvoice(BeanUtil.SOURCE_ID + "_2"));
        produceMessageToEventSink(BeanUtil.createMessageCreateInvoice(BeanUtil.SOURCE_ID + "_3"));
        produceMessageToEventSink(BeanUtil.createMessagePaymentStared(BeanUtil.SOURCE_ID));
        produceMessageToEventSink(BeanUtil.createMessagePaymentStared(BeanUtil.SOURCE_ID + "_2"));
        produceMessageToEventSink(BeanUtil.createMessageInvoiceCaptured(BeanUtil.SOURCE_ID));

        eventSinkStreamProperties.setProperty(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000 + "");
        eventSinkStreamProperties.setProperty(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0 + "");

        try (KafkaStreams kafkaStreams = eventSinkAggregationStreamFactory.create(eventSinkStreamProperties)) {

            try (Consumer<String, MgEventSinkRow> consumer = createConsumer(MgEventSinkRowDeserializer.class)) {
                consumer.subscribe(List.of(aggregatedEventSink));
                ConsumerRecords<String, MgEventSinkRow> poll = pollWithWaitingTimeout(consumer, Duration.ofSeconds(30L));

                Assert.assertFalse(poll.isEmpty());
                Iterator<ConsumerRecord<String, MgEventSinkRow>> iterator = poll.iterator();

                Assert.assertEquals(1, poll.count());
                ConsumerRecord<String, MgEventSinkRow> record = iterator.next();

                MgEventSinkRow value = record.value();
                Assert.assertEquals(ResultStatus.CAPTURED.name(), value.getResultStatus());
                Assert.assertEquals(BeanUtil.SHOP_ID, value.getShopId());
            }

        }
    }

    @NotNull
    private ConsumerRecords<String, MgEventSinkRow> pollWithWaitingTimeout(Consumer<String, MgEventSinkRow> consumer, Duration duration) {
        long startTime = System.currentTimeMillis();
        ConsumerRecords<String, MgEventSinkRow> poll = consumer.poll(Duration.ofSeconds(5L));
        while (poll.isEmpty()) {
            if (System.currentTimeMillis() - startTime > duration.toMillis()) {
                throw new RuntimeException("Timeout error in pollWithWaitingTimeout!");
            }
            poll = consumer.poll(Duration.ofSeconds(5L));
        }
        return poll;
    }


}