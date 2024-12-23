package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.List;

/**
 * @author Steven
 * @version 1.0
 * @description:
 * @date 2024/12/19 21:28
 */
@SpringBootTest
public class TypeServiceTest {

    @Resource
    private IShopTypeService typeService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testSave() {
        List<ShopType> shopTypeList = typeService.query().orderByAsc("sort").list();
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopTypeList));
    }

    @Test
    public void testQuery() {
        String shopTypeListJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
        System.out.println(shopTypeListJson);
        List<ShopType> shopTypeList = JSONUtil.toList(JSONUtil.parseArray(shopTypeListJson), ShopType.class);
        System.out.println(shopTypeList);
    }
}
