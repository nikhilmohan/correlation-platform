package com.acp.patternmanager.api;

import com.acp.patternmanager.api.dto.PatternPage;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.error.PatternNotFoundException;
import com.acp.patternmanager.store.entity.PatternEntity;
import com.acp.patternmanager.store.repo.PatternRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the Pattern Store — offset-paginated listing (filterable by lifecycle) and single-get.
 * Returns the frozen {@link PatternPage} envelope (P2-GAP-08). Excludes non-approved patterns from
 * the {@code lifecycle=approved} view that the Correlation Engine reads (criteria 13, 22).
 */
@Service
public class PatternQueryService {

    private static final Set<String> VALID_LIFECYCLES =
            Set.of("draft", "approved", "deprecated", "rejected");
    private static final Set<String> VALID_SORTS = Set.of("createdAt", "-createdAt", "lift", "-lift");

    private final PatternRepository patternRepository;
    private final PatternViewMapper mapper;

    public PatternQueryService(PatternRepository patternRepository, PatternViewMapper mapper) {
        this.patternRepository = patternRepository;
        this.mapper = mapper;
    }

    /**
     * List patterns as a {@link PatternPage} envelope.
     *
     * @param lifecycle optional lifecycle filter
     * @param limit page size (default 50)
     * @param offset page offset (default 0)
     * @param sort sort key (default {@code -createdAt})
     * @return the page envelope
     * @throws IllegalArgumentException invalid lifecycle/sort enum -> 400
     */
    @Transactional(readOnly = true)
    public PatternPage list(String lifecycle, Integer limit, Integer offset, String sort) {
        int lim = limit != null ? limit : 50;
        int off = offset != null ? offset : 0;
        String sortKey = sort != null ? sort : "-createdAt";
        if (lifecycle != null && !VALID_LIFECYCLES.contains(lifecycle)) {
            throw new IllegalArgumentException("invalid lifecycle: " + lifecycle);
        }
        if (!VALID_SORTS.contains(sortKey)) {
            throw new IllegalArgumentException("invalid sort: " + sortKey);
        }

        Sort springSort = toSort(sortKey);
        PageRequest page = PageRequest.of(off / Math.max(1, lim), lim, springSort);

        List<PatternEntity> rows;
        long total;
        if (lifecycle != null) {
            rows = patternRepository.findByLifecycle(lifecycle, page);
            total = patternRepository.countByLifecycle(lifecycle);
        } else {
            var p = patternRepository.findAll(page);
            rows = p.getContent();
            total = p.getTotalElements();
        }
        List<PatternView> items = rows.stream().map(mapper::toView).toList();
        return new PatternPage(items, total, lim, off);
    }

    /**
     * Get a single pattern by id.
     *
     * @param patternId the pattern id
     * @return the pattern view
     * @throws PatternNotFoundException unknown id -> 404
     */
    @Transactional(readOnly = true)
    public PatternView get(String patternId) {
        return mapper.toView(load(patternId));
    }

    private PatternEntity load(String patternId) {
        UUID id;
        try {
            id = UUID.fromString(patternId);
        } catch (IllegalArgumentException e) {
            throw new PatternNotFoundException(patternId);
        }
        return patternRepository.findById(id)
                .orElseThrow(() -> new PatternNotFoundException(patternId));
    }

    private Sort toSort(String sortKey) {
        boolean desc = sortKey.startsWith("-");
        String field = desc ? sortKey.substring(1) : sortKey;
        String column = switch (field) {
            case "lift" -> "lift";
            default -> "createdAt";
        };
        return desc ? Sort.by(column).descending() : Sort.by(column).ascending();
    }
}
