package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        String key = RedisConstants.SHOP_TYPE_LIST;
        //1.查缓存
        String cacheShop = stringRedisTemplate.opsForValue().get(key);
        System.out.println("json串" + cacheShop);
        //2.有缓存，返回
        if (StrUtil.isNotBlank(cacheShop)) {
            JSONArray objects = JSONUtil.parseArray(cacheShop);
            List<ShopType> shopTypes = JSONUtil.toList(objects, ShopType.class);
            return Result.ok(shopTypes);
        }
        //3.无缓存，查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //4.无信息，返回错误
        if (shopTypes.isEmpty()){
            return Result.fail("没有分类信息");
        }
        //5.有信息，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypes));
        //6.返回数据

        return Result.ok(shopTypes);
    }
}
