package org.swisspush.redisques;

import io.vertx.core.*;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.swisspush.redisques.util.RedisquesConfiguration;
import redis.clients.jedis.Jedis;

@RunWith(VertxUnitRunner.class)
public abstract class AbstractTestCase {

    static Logger log = LoggerFactory.getLogger(AbstractTestCase.class);

    protected static Vertx vertx;
    protected static Jedis jedis;

    protected static String deploymentId = "";

    protected void flushAll(){
        if(jedis != null){
            jedis.flushAll();
        }
    }

    protected void assertKeyCount(TestContext context, int keyCount){
        assertKeyCount(context, "", keyCount);
    }

    protected void assertKeyCount(TestContext context, String prefix, int keyCount){
        context.assertEquals(keyCount, jedis.keys(prefix+"*").size());
    }

    @BeforeClass
    public static void config(TestContext context) {
        if(!RedisEmbeddedConfiguration.useExternalRedis()) {
            RedisEmbeddedConfiguration.redisServer.start();
        }
    }

    @AfterClass
    public static void stopRedis(TestContext context) {
        if(!RedisEmbeddedConfiguration.useExternalRedis()) {
            RedisEmbeddedConfiguration.redisServer.stop();
        }
        jedis.close();
    }

    @Before
    public void cleanDB() {
        flushAll();
    }

    protected void eventBusSend(JsonObject operation, Handler<AsyncResult<Message<JsonObject>>> handler){
        vertx.eventBus().send("redisques", operation, handler);
    }
}