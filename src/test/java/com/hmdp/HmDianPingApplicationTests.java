package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    IShopService shopService;

    @Resource
    RedisIdWorker redisIdWorker;

    @Resource
    IBlogService blogService;

    @Resource
    IUserService userService;

    @Resource
    StringRedisTemplate stringRedisTemplate;

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

    @Test
    void testFeed() {
        UserHolder.saveUser(BeanUtil.copyProperties(userService.getById(1L), UserDTO.class));
        blogService.queryBlogOfFollow(System.currentTimeMillis(), 0);
    }

    @Test
    void loadShopData() {
        List<Shop> shopList = shopService.list();
        Map<Long, List<Shop>> typedShopMap = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        typedShopMap.forEach((k, v) -> {
            String key = RedisConstants.SHOP_GEO_KEY + k;
            List<RedisGeoCommands.GeoLocation<String>> geoLocationList = v
                    .stream()
                    .map(shop -> new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())))
                    .collect(Collectors.toList());
            stringRedisTemplate.opsForGeo().add(key, geoLocationList);
//            v.forEach(shop -> stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString()));
        });
    }

    @Test
    public void HLLTest() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
            }
        }
        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }
}
