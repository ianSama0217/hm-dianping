package com.hmdp;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.hmdp.service.impl.ShopServiceImpl;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Test
    void testShopService() {
        shopService.saveShop2Redis(1L, 10L);
    }
}
