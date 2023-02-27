package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;

    @GetMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        System.out.println("id" + voucherId);
        return voucherOrderService.seckillVoucher(voucherId);
    }

    @GetMapping("/hello")
    public Result hello(){
        return Result.ok("hello, world");
    }
}
