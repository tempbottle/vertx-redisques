package org.swisspush.redisques;

import io.vertx.core.AsyncResult;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Timeout;
import org.junit.BeforeClass;
import org.junit.Ignore;
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
 * Class RedisQueueBrowserTest.
 *
 * @author baldim, webermarca
 */
public class RedisQuesTest extends AbstractTestCase {

    public static final String TIMESTAMP = "timestamp";
    public static final String QUEUES_PREFIX = "redisques:queues:";
    public static final String REDISQUES_LOCKS = "redisques:locks";

    @Rule
    public Timeout rule = Timeout.seconds(5);

    @BeforeClass
    public static void deployRedisques(TestContext context) {
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
    public void testUnsupportedOperation(TestContext context) {
        Async async = context.async();
        JsonObject op = new JsonObject();
        op.put(OPERATION, "some_unkown_operation");
        eventBusSend(op, message -> {
            context.assertEquals(ERROR, message.result().body().getString(STATUS));
            context.assertEquals("QUEUE_ERROR: Unsupported operation received: some_unkown_operation", message.result().body().getString(MESSAGE));
            async.complete();
        });
    }

    @Test
    public void enqueue(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, QUEUES_PREFIX, 0);
        eventBusSend(buildEnqueueOperation("queueEnqueue", "helloEnqueue"), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            context.assertEquals("helloEnqueue", jedis.lindex(QUEUES_PREFIX + "queueEnqueue", 0));
            assertKeyCount(context, QUEUES_PREFIX, 1);
            async.complete();
        });
    }

    @Test
    public void getQueueItems(TestContext context) {
        Async async = context.async();
        flushAll();
        eventBusSend(buildGetQueueItemsOperation("queue1", null), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            context.assertEquals(0, message.result().body().getJsonArray(VALUE).size());
            eventBusSend(buildEnqueueOperation("queue1", "a_queue_item"), message1 -> {
                context.assertEquals(OK, message1.result().body().getString(STATUS));
                assertKeyCount(context, QUEUES_PREFIX, 1);
                eventBusSend(buildGetQueueItemsOperation("queue1", null), event -> {
                    context.assertEquals(OK, event.result().body().getString(STATUS));
                    context.assertEquals(1, event.result().body().getJsonArray(VALUE).size());
                    context.assertEquals("a_queue_item", event.result().body().getJsonArray(VALUE).getString(0));
                    context.assertEquals(1, event.result().body().getJsonArray(INFO).getInteger(0));
                    context.assertEquals(1, event.result().body().getJsonArray(INFO).getInteger(1));
                    async.complete();
                });
            });
        });
    }

    @Test
    public void getQueues(TestContext context) {
        Async asyncEnqueue = context.async(100);
        flushAll();
        assertKeyCount(context, QUEUES_PREFIX, 0);
        for (int i = 0; i < 100; i++) {
            eventBusSend(buildEnqueueOperation("queue" + i, "testItem"), message -> {
                context.assertEquals(OK, message.result().body().getString(STATUS));
                asyncEnqueue.countDown();
            });
        }
        asyncEnqueue.awaitSuccess();

        assertKeyCount(context, QUEUES_PREFIX, 100);
        Async async = context.async();
        eventBusSend(buildGetQueuesOperation(), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            JsonArray queuesArray = message.result().body().getJsonObject(VALUE).getJsonArray("queues");
            context.assertEquals(100, queuesArray.size());
            for (int i = 0; i < 100; i++) {
                context.assertTrue(queuesArray.contains("queue"+i), "item queue" + i + " expected to be in result");
            }
            async.complete();
        });
    }

    @Test
    public void getQueuesCount(TestContext context) {
        Async asyncEnqueue = context.async(100);
        flushAll();
        assertKeyCount(context, QUEUES_PREFIX, 0);
        for (int i = 0; i < 100; i++) {
            eventBusSend(buildEnqueueOperation("queue" + i, "testItem"), message -> {
                context.assertEquals(OK, message.result().body().getString(STATUS));
                asyncEnqueue.countDown();
            });
        }
        asyncEnqueue.awaitSuccess();

        assertKeyCount(context, QUEUES_PREFIX, 100);
        Async async = context.async();
        eventBusSend(buildGetQueuesCountOperation(), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            context.assertEquals(100L, message.result().body().getLong(VALUE));
            async.complete();
        });
    }

    @Test
    public void getQueueItemsCount(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, QUEUES_PREFIX, 0);
        String queue = "queue_1";
        for (int i = 0; i < 100; i++) {
            jedis.rpush(QUEUES_PREFIX + queue, "testItem"+i);
        }
        assertKeyCount(context, QUEUES_PREFIX, 1);
        context.assertEquals(100L, jedis.llen(QUEUES_PREFIX + queue));
        eventBusSend(buildGetQueueItemsCountOperation(queue), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            context.assertEquals(100L, message.result().body().getLong(VALUE));
            async.complete();
        });
    }

    @Test
    public void deleteAllQueueItems(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, QUEUES_PREFIX, 0);
        final String queue = "queue1";
        eventBusSend(buildEnqueueOperation(queue, "some_val"), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            assertKeyCount(context, QUEUES_PREFIX, 1);
            eventBusSend(buildDeleteAllQueueItemsOperation(queue), message1 -> {
                context.assertEquals(OK, message1.result().body().getString(STATUS));
                assertKeyCount(context, QUEUES_PREFIX, 0);
                async.complete();
            });
        });
    }

    @Test
    public void addQueueItem(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, QUEUES_PREFIX, 0);
        eventBusSend(buildAddQueueItemOperation("queue2", "fooBar"), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            assertKeyCount(context, QUEUES_PREFIX, 1);
            context.assertEquals("fooBar", jedis.lindex(QUEUES_PREFIX + "queue2", 0));
            async.complete();
        });
    }

    @Test
    public void addQueueItemWithLegacyOperationName(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, QUEUES_PREFIX, 0);

        JsonObject op = new JsonObject();
        op.put(OPERATION, "addItem");
        op.put(PAYLOAD, new JsonObject().put(QUEUENAME, "queue2").put("buffer", "fooBar"));

        eventBusSend(op, message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            assertKeyCount(context, QUEUES_PREFIX, 1);
            context.assertEquals("fooBar", jedis.lindex(QUEUES_PREFIX + "queue2", 0));
            async.complete();
        });
    }

    @Test
    public void getQueueItemsWithQueueSizeInformation(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, QUEUES_PREFIX, 0);
        eventBusSend(buildAddQueueItemOperation("queue2", "fooBar"), message -> {
            eventBusSend(buildAddQueueItemOperation("queue2", "fooBar2"), message1 -> {
                eventBusSend(buildAddQueueItemOperation("queue2", "fooBar3"), message2 -> {
                    context.assertEquals(OK, message2.result().body().getString(STATUS));
                    eventBusSend(buildGetQueueItemsOperation("queue2", "2"), event -> {
                        context.assertEquals(OK, event.result().body().getString(STATUS));
                        context.assertEquals(2, event.result().body().getJsonArray(VALUE).size());
                        context.assertEquals(2, event.result().body().getJsonArray(INFO).getInteger(0));
                        context.assertEquals(3, event.result().body().getJsonArray(INFO).getInteger(1));
                        async.complete();
                    });
                });
            });
        });
    }

    @Test
    public void getQueueItem(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, QUEUES_PREFIX, 0);
        eventBusSend(buildGetQueueItemOperation("queue1", 0), message -> {
            context.assertEquals(ERROR, message.result().body().getString(STATUS));
            context.assertNull(message.result().body().getString(VALUE));
            eventBusSend(buildAddQueueItemOperation("queue1", "fooBar"), message1 -> {
                context.assertEquals(OK, message1.result().body().getString(STATUS));
                assertKeyCount(context, QUEUES_PREFIX, 1);
                context.assertEquals("fooBar", jedis.lindex(QUEUES_PREFIX + "queue1", 0));
                eventBusSend(buildGetQueueItemOperation("queue1", 0), message2 -> {
                    context.assertEquals(OK, message2.result().body().getString(STATUS));
                    context.assertEquals("fooBar", message2.result().body().getString("value"));
                    async.complete();
                });
            });
        });
    }

    @Test
    public void replaceQueueItem(TestContext context) {
        Async async = context.async();
        flushAll();
        eventBusSend(buildAddQueueItemOperation("queue1", "foo"), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            assertKeyCount(context, QUEUES_PREFIX, 1);
            context.assertEquals("foo", jedis.lindex(QUEUES_PREFIX + "queue1", 0));
            eventBusSend(buildReplaceQueueItemOperation("queue1", 0, "bar"), message1 -> {
                context.assertEquals(OK, message1.result().body().getString(STATUS));
                context.assertEquals("bar", jedis.lindex(QUEUES_PREFIX + "queue1", 0));
                async.complete();
            });
        });
    }

    @Test
    public void replaceQueueItemWithLegacyOperationName(TestContext context) {
        Async async = context.async();
        flushAll();
        eventBusSend(buildAddQueueItemOperation("queue1", "foo"), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            assertKeyCount(context, QUEUES_PREFIX, 1);
            context.assertEquals("foo", jedis.lindex(QUEUES_PREFIX + "queue1", 0));

            JsonObject op = new JsonObject();
            op.put(OPERATION, "replaceItem");
            op.put(PAYLOAD, new JsonObject().put(QUEUENAME, "queue1").put("index", 0).put("buffer", "bar"));

            eventBusSend(op, message1 -> {
                context.assertEquals(OK, message1.result().body().getString(STATUS));
                context.assertEquals("bar", jedis.lindex(QUEUES_PREFIX + "queue1", 0));
                async.complete();
            });
        });
    }

    @Test
    public void deleteQueueItem(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, QUEUES_PREFIX + "queue1", 0);
        eventBusSend(buildDeleteQueueItemOperation("queue1", 0), message -> {
            context.assertEquals(ERROR, message.result().body().getString(STATUS));
            eventBusSend(buildAddQueueItemOperation("queue1", "foo"), message2 -> {
                context.assertEquals(OK, message2.result().body().getString(STATUS));
                assertKeyCount(context, QUEUES_PREFIX + "queue1", 1);
                eventBusSend(buildDeleteQueueItemOperation("queue1", 0), message3 -> {
                    context.assertEquals(OK, message3.result().body().getString(STATUS));
                    assertKeyCount(context, QUEUES_PREFIX + "queue1", 0);
                    async.complete();
                });
            });
        });
    }

    @Test
    public void getAllLocks(TestContext context) {
        Async async = context.async();
        flushAll();
        eventBusSend(buildGetAllLocksOperation(), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            JsonArray locksArray = message.result().body().getJsonObject(VALUE).getJsonArray("locks");
            context.assertNotNull(locksArray, "locks array should not be null");
            context.assertEquals(0, locksArray.size(), "locks array should be empty");
            eventBusSend(buildPutLockOperation("testLock", "geronimo"), message2 -> {
                context.assertEquals(OK, message2.result().body().getString(STATUS));
                context.assertTrue(jedis.hexists(REDISQUES_LOCKS, "testLock"));
                eventBusSend(buildGetAllLocksOperation(), message3 -> {
                    context.assertEquals(OK, message3.result().body().getString(STATUS));
                    JsonArray locksArray1 = message3.result().body().getJsonObject(VALUE).getJsonArray("locks");
                    context.assertNotNull(locksArray1, "locks array should not be null");
                    context.assertTrue(locksArray1.size() > 0, "locks array should not be empty");
                    if(locksArray1.size() > 0) {
                        String result = locksArray1.getString(0);
                        context.assertTrue(result.matches("testLock.*"));
                    }
                    async.complete();
                });
            });
        });
    }

    @Test
    public void putLock(TestContext context) {
        Async async = context.async();
        flushAll();
        eventBusSend(buildPutLockOperation("queue1", "someuser"), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            context.assertTrue(jedis.hexists(REDISQUES_LOCKS, "queue1"));
            assertLockContent(context, "queue1", "someuser");
            async.complete();
        });
    }

    @Test
    public void putLockMissingRequestedBy(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, REDISQUES_LOCKS, 0);
        context.assertFalse(jedis.hexists(REDISQUES_LOCKS, "queue1"));
        eventBusSend(buildOperation(QueueOperation.putLock, new JsonObject().put(QUEUENAME, "queue1")), message -> {
            context.assertEquals(ERROR, message.result().body().getString(STATUS));
            context.assertEquals("Property '"+REQUESTED_BY+"' missing", message.result().body().getString(MESSAGE));
            assertKeyCount(context, REDISQUES_LOCKS, 0);
            context.assertFalse(jedis.hexists(REDISQUES_LOCKS, "queue1"));
            async.complete();
        });
    }

    @Test
    public void getLock(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, REDISQUES_LOCKS, 0);
        eventBusSend(buildPutLockOperation("testLock1", "geronimo"), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            context.assertTrue(jedis.hexists(REDISQUES_LOCKS, "testLock1"));
            assertKeyCount(context, REDISQUES_LOCKS, 1);
            eventBusSend(buildGetLockOperation("testLock1"), message1 -> {
                context.assertEquals(OK, message1.result().body().getString(STATUS));
                context.assertTrue(jedis.hexists(REDISQUES_LOCKS, "testLock1"));
                assertLockContent(context, "testLock1", "geronimo");
                async.complete();
            });
        });
    }

    @Test
    public void getNotExistingLock(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, REDISQUES_LOCKS, 0);
        eventBusSend(buildGetLockOperation("notExistingLock"), message -> {
            context.assertEquals(NO_SUCH_LOCK, message.result().body().getString(STATUS));
            assertKeyCount(context, REDISQUES_LOCKS, 0);
            context.assertFalse(jedis.hexists(REDISQUES_LOCKS, "notExistingLock"));
            async.complete();
        });
    }

    @Test
    public void testIgnoreCaseInOperationName(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, REDISQUES_LOCKS, 0);

        JsonObject op = new JsonObject();
        op.put(OPERATION, "GeTLOcK");
        op.put(PAYLOAD, new JsonObject().put(QUEUENAME, "notExistingLock"));

        eventBusSend(op, message -> {
            context.assertEquals(NO_SUCH_LOCK, message.result().body().getString(STATUS));
            assertKeyCount(context, REDISQUES_LOCKS, 0);
            context.assertFalse(jedis.hexists(REDISQUES_LOCKS, "notExistingLock"));
            async.complete();
        });
    }

    @Test
    public void deleteNotExistingLock(TestContext context) {
        Async async = context.async();
        flushAll();
        eventBusSend(buildDeleteLockOperation("testLock1"), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            context.assertFalse(jedis.hexists(REDISQUES_LOCKS, "testLock1"));
            async.complete();
        });
    }

    @Test
    public void deleteExistingLock(TestContext context) {
        Async async = context.async();
        flushAll();
        assertKeyCount(context, REDISQUES_LOCKS, 0);
        eventBusSend(buildPutLockOperation("testLock1", "someuser"), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            context.assertTrue(jedis.hexists(REDISQUES_LOCKS, "testLock1"));
            assertKeyCount(context, REDISQUES_LOCKS, 1);
            eventBusSend(buildDeleteLockOperation("testLock1"), message1 -> {
                context.assertEquals(OK, message1.result().body().getString(STATUS));
                context.assertFalse(jedis.hexists(REDISQUES_LOCKS, "testLock1"));
                assertKeyCount(context, REDISQUES_LOCKS, 0);
                async.complete();
            });
        });
    }

    @Test
    public void checkLimit(TestContext context) {
        Async async = context.async();
        flushAll();
        for (int i = 0; i < 250; i++) {
            jedis.rpush(QUEUES_PREFIX + "testLock1", "testItem"+i);
        }
        eventBusSend(buildGetQueueItemsOperation("testLock1", "178"), message -> {
            context.assertEquals(OK, message.result().body().getString(STATUS));
            context.assertEquals(178, message.result().body().getJsonArray(VALUE).size());
            context.assertEquals(178, message.result().body().getJsonArray(INFO).getInteger(0));
            context.assertEquals(250, message.result().body().getJsonArray(INFO).getInteger(1));
            async.complete();
        });
    }

    private void assertLockContent(TestContext context, String queuename, String expectedRequestedByValue){
        String item = jedis.hget(REDISQUES_LOCKS, queuename);
        context.assertNotNull(item);
        if(item != null){
            JsonObject lockInfo = new JsonObject(item);
            context.assertNotNull(lockInfo.getString(REQUESTED_BY), "Property '"+REQUESTED_BY+"' missing");
            context.assertNotNull(lockInfo.getLong(TIMESTAMP), "Property '"+TIMESTAMP+"' missing");
            context.assertEquals(expectedRequestedByValue, lockInfo.getString(REQUESTED_BY), "Property '"+REQUESTED_BY+"' has wrong value");
        }
    }

}
