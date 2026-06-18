package com.retailstore.catalog.application.port.in;

import com.retailstore.catalog.api.rest.v1.dto.response.TagResponse;
import java.util.List;

public interface GetTagUseCase {
    List<TagResponse> getAllTags();
}
