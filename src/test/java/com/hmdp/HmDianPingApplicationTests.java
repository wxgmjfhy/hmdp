package com.hmdp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.hmdp.utils.RedisIdWorker;

import jakarta.annotation.Resource;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    /*
     * 测试 RedisIdWorker
     */
    @Test
    void testIdWorker() throws InterruptedException {
        // 内部变量初始化为 300
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            // 内部变量 -1
            latch.countDown();
        };

        long begin = System.currentTimeMillis();
        
        // 执行分线程
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        
        // 内部变量为 0 时, 不再阻塞主线程
        latch.await();
        
        long end = System.currentTimeMillis();
        
        System.out.println("time = " + (end - begin));
    }
}
