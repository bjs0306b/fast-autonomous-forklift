package com.fast.backend.storage.placement;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 적재 추천 설정·빈 등록. {@link PlacementProperties}를 활성화하고
 * {@link PlacementService}를 빈으로 노출한다. {@code PlacementService}는 Spring에 의존하지 않는 순수
 * 로직 클래스라 단위 테스트에서는 직접 생성해서 쓴다.
 */
@Configuration
@EnableConfigurationProperties(PlacementProperties.class)
public class PlacementConfig {

    @Bean
    public PlacementService placementService(PlacementProperties placementProperties) {
        return new PlacementService(placementProperties);
    }
}
