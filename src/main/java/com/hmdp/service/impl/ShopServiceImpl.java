package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    RedissonClient redissonClient;

    @Resource
    CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            10,
            10,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>()
    );

    @Override
    public Result queryById(Long id) {
//        Shop shop = queryByIdWithLogicalExpire(id);

//        Shop shop = cacheClient.queryByIdWithPassThrough(
//                RedisConstants.CACHE_SHOP_KEY,
//                id,
//                Shop.class,
//                this::getById,
//                RedisConstants.CACHE_SHOP_TTL,
//                TimeUnit.MINUTES);

//        Shop shop = cacheClient.queryByIdWithLogicalExpire(
//                RedisConstants.CACHE_SHOP_KEY,
//                RedisConstants.LOCK_SHOP_KEY,
//                id,
//                Shop.class,
//                this::getById,
//                RedisConstants.CACHE_SHOP_TTL,
//                TimeUnit.MINUTES
//        );

        Shop shop = cacheClient.queryByIdWithRedisson(RedisConstants.CACHE_SHOP_KEY,
                RedisConstants.LOCK_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                RedisConstants.CACHE_SHOP_TTL,
                TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private Shop queryByIdWithLogicalExpire(Long id) {
        // 从缓存中取值
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        if(StrUtil.isBlank(shopJson)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        // 如果没有过期则返回值
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return shop;
        }

        // 过期则开新线程更新缓存
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        if(tryLock(lockKey)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShop2Redis(id, RedisConstants.CACHE_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }

        // 返回旧值
        return shop;
    }

    private Shop queryByIdWithMutex(Long id) {
        // 查询缓存中是否存在
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        Shop shop = null;

        // 缓存中存在，直接返回
        if(StrUtil.isNotBlank(shopJson)) {
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // 命中的是空值，即缓存中存在且是空值
        if(shopJson != null) {
            return null;
        }

        // 缓存中不存在，查询数据库，并更新缓存,防止缓存击穿，使用互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        while(!tryLock(lockKey)) {
            // 获取锁失败，休眠
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        try {
            // 双重判断
            if(StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            shop = getById(id);
            Thread.sleep(200);
            if(shop == null) {
                // 更新缓存空值
                stringRedisTemplate.opsForValue().set(shopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }

        return shop;
    }

    private Shop queryByIdWithPassThrough(Long id) {
        // 查询缓存中是否存在
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        Shop shop = null;

        // 缓存中存在，直接返回
        if(StrUtil.isNotBlank(shopJson)) {
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // 命中的是空值，即缓存中存在且是空值
        if(shopJson != null) {
            return null;
        }

        // 缓存中不存在，查询数据库，并更新缓存
        shop = getById(id);
        if(shop == null) {
            // 更新缓存空值
            stringRedisTemplate.opsForValue().set(shopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    private Shop queryByIdWithRedisson(Long id) {
        // 查询缓存中是否存在
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        Shop shop = null;

        // 缓存中存在，直接返回
        if(StrUtil.isNotBlank(shopJson)) {
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // 命中的是空值，即缓存中存在且是空值
        if(shopJson != null) {
            return null;
        }

        // 缓存中不存在，查询数据库，并更新缓存，使用双重判定锁保证只有一个线程更新缓存（防止缓存击穿）
        String lockShopKey = RedisConstants.LOCK_SHOP_KEY + id;
        RLock lock = redissonClient.getLock(lockShopKey);
        lock.lock();

        try {
            // 双重判定锁
            shopJson = stringRedisTemplate.opsForValue().get(shopKey);
            if(StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            shop = getById(id);
            if(shop == null) {
                // 更新缓存空值
                stringRedisTemplate.opsForValue().set(shopKey, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

        return shop;
    }

    private boolean tryLock(String lockKey) {
        Boolean b = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(b);
    }

    private void unLock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) {
        Shop shop = getById(id);

        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if(shop == null || shop.getId() == null) {
            return Result.fail("店铺id为空");
        }
        updateById(shop);

        stringRedisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
