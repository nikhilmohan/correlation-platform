package com.acp.patternmanager.store.repo;

import com.acp.patternmanager.store.entity.LifecycleTransitionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the lifecycle-transition audit log. */
public interface LifecycleTransitionRepository extends JpaRepository<LifecycleTransitionEntity, UUID> {

    List<LifecycleTransitionEntity> findByPatternIdOrderByTransitionedAtAsc(UUID patternId);
}
