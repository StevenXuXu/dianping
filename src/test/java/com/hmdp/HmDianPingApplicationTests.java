package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    ShopServiceImpl shopService;

    @Autowired
    RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(300);

    @Test
    void testIdWorker() {
        CountDownLatch countDownLatch = new CountDownLatch(300);

        for(int i = 0; i < 300; i++) {
            es.submit(() -> {
                for(int j = 0; j < 100; j++) {
                    long id = redisIdWorker.nextId("order");
//                    System.out.println("id = " + id);
                }
                countDownLatch.countDown();
            });
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
