package com.fast.backend.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis {@code @Mapper} 인터페이스 스캔 위치를 한 곳에서 관리한다. 새 도메인이 Mapper를 추가하면
 * 이 패키지 목록에 추가하면 된다(패키지마다 별도 스캔 설정을 반복하지 않기 위함).
 */
@Configuration
@MapperScan(basePackages = {
        "com.fast.backend.vehicle.mapper", "com.fast.backend.station.mapper",
        "com.fast.backend.command.mapper", "com.fast.backend.storage.mapper",
        "com.fast.backend.transport.mapper", "com.fast.backend.traffic.mapper"
})
public class MyBatisConfig {
}
