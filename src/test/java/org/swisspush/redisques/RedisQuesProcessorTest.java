package org.swisspush.redisques;

import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.swisspush.redisques.util.RedisquesConfiguration;
import redis.clients.jedis.Jedis;

import javax.xml.bind.DatatypeConverter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.swisspush.redisques.util.RedisquesAPI.*;

/**
 * Created by florian kammermann on 31.05.2016.
 */
public class RedisQuesProcessorTest extends AbstractTestCase {

    public static final int NUM_QUEUES = 10;

    private static MessageConsumer<JsonObject> queueProcessor = null;

    @Rule
    public Timeout rule = Timeout.seconds(300);

    @BeforeClass
    public static void createQueueProcessor(TestContext context) {
        deployRedisques(context);
        queueProcessor = vertx.eventBus().consumer("processor-address");
    }

    protected static void deployRedisques(TestContext context) {
        vertx = Vertx.vertx();
        JsonObject config = RedisquesConfiguration.with()
                .processorAddress("processor-address")
                .redisEncoding("ISO-8859-1")
                .refreshPeriod(2)
                .build()
                .asJsonObject();

        RedisQues redisQues = new RedisQues();
        vertx.deployVerticle(redisQues, new DeploymentOptions().setConfig(config), context.asyncAssertSuccess(event -> {
            deploymentId = event;
            log.info("vert.x Deploy - " + redisQues.getClass().getSimpleName() + " was successful.");
            jedis = new Jedis("localhost", 6379, 5000);
        }));
    }

    @Test
    public void test10Queues(TestContext context) throws Exception {

        final Map<String, MessageDigest> signatures = new HashMap<>();

        queueProcessor.handler(message -> {
            final String queue = message.body().getString("queue");
            final String payload = message.body().getString("payload");

            if ("STOP".equals(payload)) {
                log.info("STOP message " + payload);
                message.reply(new JsonObject().put(STATUS, OK));
                vertx.eventBus().send("digest-" + queue, DatatypeConverter.printBase64Binary(signatures.get(queue).digest()));
            } else {
                MessageDigest signature = signatures.get(queue);
                if (signature == null) {
                    try {
                        signature = MessageDigest.getInstance("MD5");
                        signatures.put(queue, signature);
                    } catch (NoSuchAlgorithmException e) {
                        throw new RuntimeException();
                    }
                }
                signature.update(payload.getBytes());
                log.info("added queue ["+queue+"] signature ["+digestStr(signature)+"]");
            }

            vertx.setTimer(new Random().nextLong() % 1 + 1, event -> {
                log.info("Processed message " + payload);
                message.reply(new JsonObject().put(STATUS, OK));
            });
        });

        Async async = context.async();
        flushAll();
        assertKeyCount(context, 0);
        for (int i = 0; i < NUM_QUEUES; i++) {
            log.info("create new sender for queue: queue_" + i );
            new Sender(context, async, "queue_" + i).send(null);
        }
    }

    private String digestStr(MessageDigest digest) {
        return DatatypeConverter.printBase64Binary(digest.digest());
    }

    int numMessages = 5;
    AtomicInteger finished = new AtomicInteger();

    /**
     * Sender Class
     */
    class Sender {
        final String queue;
        int messageCount;
        MessageDigest signature;
        TestContext context;
        Async async;

        Sender(TestContext context, Async async, final String queue) {
            this.context = context;
            this.async = async;
            this.queue = queue;
            try {
                signature = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }

            vertx.eventBus().consumer("digest-" + queue, new Handler<Message<String>>() {
                @Override
                public void handle(Message<String> event) {
                    log.info("Received signature for " + queue + ": " + event.body());
                    if(! event.body().equals(DatatypeConverter.printBase64Binary(signature.digest()))) {
                        log.error("signatures are not identical: " + event.body() + " != " + DatatypeConverter.printBase64Binary(signature.digest()));
                    }
                    context.assertEquals(event.body(), DatatypeConverter.printBase64Binary(signature.digest()), "Signatures differ");
                    if (finished.incrementAndGet() == NUM_QUEUES) {
                        async.complete();
                    }
                }
            });
        }

        void send(final String m) {
            if (messageCount < numMessages) {
                final String message;
                if(m==null) {
                    message = Double.toString(Math.random());
                } else {
                    message = m;
                }
                signature.update(message.getBytes());
                log.info("send message ["+digestStr(signature)+"] for queue ["+queue+"] ");
                vertx.eventBus().send("redisques", buildEnqueueOperation(queue, message), new Handler<AsyncResult<Message<JsonObject>>>() {
                    @Override
                    public void handle(AsyncResult<Message<JsonObject>> event) {
                        if(event.result().body().getString(STATUS).equals(OK)) {
                            send(null);
                        } else {
                            log.error("ERROR sending "+message+" to "+queue);
                            send(message);
                        }
                    }
                });
                messageCount++;
            } else {
                vertx.eventBus().send("redisques", buildEnqueueOperation(queue, "STOP"), new Handler<AsyncResult<Message<JsonObject>>>() {
                    @Override
                    public void handle(AsyncResult<Message<JsonObject>> reply) {
                        context.assertEquals(OK, reply.result().body().getString(STATUS));
                    }
                });
            }
        }
    }

    @Test
    public void enqueueWithQueueProcessor(TestContext context) throws Exception {
        Async async = context.async();
        flushAll();
        queueProcessor.handler(message -> {
            // assert the values we sent too
            context.assertEquals("check-queue", message.body().getString("queue"));
            context.assertEquals("hello", message.body().getString("payload"));

            // assert the value is still in the redis store
            String queueValueInRedis = jedis.lindex("redisques:queues:check-queue", 0);
            context.assertEquals("hello", queueValueInRedis);
            // assert that there is a consumer assigned
            String consumer = jedis.get("redisques:consumers:check-queue");
            context.assertNotNull(consumer);

            // reply to redisques with OK, which will delete the queue entry and release the consumer
            message.reply(new JsonObject().put(STATUS, OK));
            sleep(500);

            // assert that the queue is empty now
            queueValueInRedis = jedis.lindex("redisques:queues:check-queue", 0);
            context.assertNull(queueValueInRedis);

            // end the test
            async.complete();
        });



        final JsonObject operation = buildEnqueueOperation("check-queue", "hello");
        eventBusSend(operation, reply -> {
            context.assertEquals(OK, reply.result().body().getString(STATUS));
        });
    }

    @Test
    public void enqueueWithQueueProcessorFirstProcessFails(TestContext context) throws Exception {
        Async async = context.async();
        flushAll();

        final AtomicInteger queueProcessorCounter = new AtomicInteger(0);

        queueProcessor.handler(message -> {

            int queueProcessCount = queueProcessorCounter.incrementAndGet();

            // assert the values we sent too
            context.assertEquals("check-queue", message.body().getString("queue"));
            context.assertEquals("hello", message.body().getString("payload"));

            // assert the value is still in the redis store
            String queueValueInRedis = jedis.lindex("redisques:queues:check-queue", 0);
            context.assertEquals("hello", queueValueInRedis);
            // assert that there is a consumer assigned
            String consumer = jedis.get("redisques:consumers:check-queue");
            context.assertNotNull(consumer);


            if (queueProcessCount == 1) {

                // reply to redisques with OK, which will delete the queue entry and release the consumer
                message.reply(new JsonObject().put(STATUS, ERROR));

                sleep(500);

                // assert that value is still in the queue
                queueValueInRedis = jedis.lindex("redisques:queues:check-queue", 0);
                context.assertEquals("hello", queueValueInRedis);
            } else {
                message.reply(new JsonObject().put(STATUS, OK));
                sleep(500);

                // assert that the queue is empty now
                queueValueInRedis = jedis.lindex("redisques:queues:check-queue", 0);
                context.assertNull(queueValueInRedis);

                // end the test
                async.complete();
            }
        });

        final JsonObject operation = buildEnqueueOperation("check-queue", "hello");
        eventBusSend(operation, reply -> {
            context.assertEquals(OK, reply.result().body().getString(STATUS));
        });
    }

    @Test
    public void enqueueWithQueueProcessorFirstProcessNoResponse(TestContext context) throws Exception {
        Async async = context.async();
        flushAll();

        final AtomicInteger queueProcessorCounter = new AtomicInteger(0);

        queueProcessor.handler(message -> {

            int queueProcessCount = queueProcessorCounter.incrementAndGet();

            // assert the values we sent too
            context.assertEquals("check-queue", message.body().getString("queue"));
            context.assertEquals("hello", message.body().getString("payload"));

            // assert the value is still in the redis store
            String queueValueInRedis = jedis.lindex("redisques:queues:check-queue", 0);
            context.assertEquals("hello", queueValueInRedis);
            // assert that there is a consumer assigned
            String consumer = jedis.get("redisques:consumers:check-queue");
            context.assertNotNull(consumer);


            if (queueProcessCount == 1) {
                // assert that value is still in the queue
                queueValueInRedis = jedis.lindex("redisques:queues:check-queue", 0);
                context.assertEquals("hello", queueValueInRedis);
            } else {
                message.reply(new JsonObject().put(STATUS, OK));
                sleep(500);

                // assert that the queue is empty now
                queueValueInRedis = jedis.lindex("redisques:queues:check-queue", 0);
                context.assertNull(queueValueInRedis);

                // end the test
                async.complete();
            }
        });

        final JsonObject operation = buildEnqueueOperation("check-queue", "hello");
        eventBusSend(operation, reply -> {
            context.assertEquals(OK, reply.result().body().getString(STATUS));
        });
    }

    private void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new IllegalStateException("can not handle interrups on sleeps");
        }
    }
}
