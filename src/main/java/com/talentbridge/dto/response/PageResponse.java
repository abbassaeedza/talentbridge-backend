package com.talentbridge.dto.response;
import lombok.*;
import java.util.List;
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class PageResponse<T> {
    private List<T> content; private int totalPages; private long totalElements;
    private int page; private int size; private boolean first; private boolean last;
}
