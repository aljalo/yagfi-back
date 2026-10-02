package com.github.regyl.gfi.service.impl.feed.cyclonedx;

import com.github.regyl.gfi.annotation.DefaultUnitTest;
import com.github.regyl.gfi.controller.dto.cyclonedx.sbom.SbomComponentDto;
import com.github.regyl.gfi.controller.dto.cyclonedx.sbom.SbomResponseDto;
import com.github.regyl.gfi.entity.UserFeedDependencyEntity;
import com.github.regyl.gfi.entity.UserFeedRequestEntity;
import com.github.regyl.gfi.model.SbomModel;
import com.github.regyl.gfi.repository.UserFeedDependencyRepository;
import com.github.regyl.gfi.service.feed.PurlToHomepageService;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@DefaultUnitTest
class CycloneDxSbomConsumerImplTest {

    @Mock
    UserFeedDependencyRepository repository;
    @Mock
    PurlToHomepageService homepage;
    @Mock
    BiFunction<SbomModel, String, UserFeedDependencyEntity> mapper;

    @Test
    void databaseFailurePropagatesToCompletionFuture() {
        var component = new SbomComponentDto();
        component.setPurl("pkg:github/example/project@1.0.0");
        var response = new SbomResponseDto();
        response.setComponents(List.of(component));
        var model = new SbomModel(new UserFeedRequestEntity(), response, "https://github.com/example/app");
        when(homepage.test(any())).thenReturn(true);
        when(homepage.apply(any())).thenReturn("https://github.com/example/project");
        when(mapper.apply(any(), any())).thenReturn(new UserFeedDependencyEntity());
        doThrow(new IllegalStateException("database unavailable")).when(repository).saveAll(any());
        var service = new CycloneDxSbomConsumerImpl(repository, List.of(homepage), mapper);
        assertThatThrownBy(() -> service.accept(model)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");
    }
}
