package com.rbkmoney.fraudbusters;

import com.rbkmoney.damsel.fraudbusters.Command;
import com.rbkmoney.damsel.fraudbusters.CommandBody;
import com.rbkmoney.damsel.fraudbusters.Template;
import com.rbkmoney.damsel.fraudbusters.TemplateReference;
import com.rbkmoney.fraudbusters.constant.TemplateLevel;
import com.rbkmoney.fraudbusters.serde.CommandDeserializer;
import com.rbkmoney.fraudbusters.template.pool.Pool;
import com.rbkmoney.fraudo.FraudoParser;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.rnorth.ducttape.unreliables.Unreliables;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

@RunWith(SpringRunner.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = RANDOM_PORT, classes = FraudBustersApplication.class)
public class DispatchTemplateTest extends KafkaAbstractTest {

    public static final String TEMPLATE = "rule: 12 >= 1\n" +
            " -> accept;";

    @Autowired
    private Pool<FraudoParser.ParseContext> pool;
    @Autowired
    private Pool<String> referencePoolImpl;

    @Test
    public void testPools() throws ExecutionException, InterruptedException {

        String id = UUID.randomUUID().toString();

        produceTemplate(id, TEMPLATE);

        //check message in topic
        try (Consumer<String, Object> consumer = createConsumer(CommandDeserializer.class)) {
            consumer.subscribe(List.of(templateTopic));
            Unreliables.retryUntilTrue(10, TimeUnit.SECONDS, () -> {
                ConsumerRecords<String, Object> records = consumer.poll(Duration.ofSeconds(1L));
                return !records.isEmpty();
            });
        }

        //check parse context created
        FraudoParser.ParseContext parseContext = pool.get(id);
        Assert.assertNotNull(parseContext);

        //create global template reference
        try (Producer<String, Command> producer = createProducer()) {
            Command command = new Command();
            TemplateReference value = new TemplateReference();
            value.setIsGlobal(true);
            value.setTemplateId(id);
            command.setCommandBody(CommandBody.reference(value));
            command.setCommandType(com.rbkmoney.damsel.fraudbusters.CommandType.CREATE);
            ProducerRecord<String, Command> producerRecord = new ProducerRecord<>(referenceTopic,
                    TemplateLevel.GLOBAL.name(), command);
            producer.send(producerRecord).get();
        }

        //check that global reference created
        Unreliables.retryUntilTrue(10, TimeUnit.SECONDS, () -> {
            String result = referencePoolImpl.get(TemplateLevel.GLOBAL.name());
            if (StringUtils.isEmpty(result)) {
                return false;
            }
            Assert.assertEquals(id, result);
            return true;
        });
    }

}