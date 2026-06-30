"""Knowledge param + feature-config loading and runtime refresh (DA-6, DA-8).

``ParamLoader.load`` fetches the current model params + feature config from the Knowledge Service
and atomically swaps them into the ``ParamStore`` / ``FeatureConfig`` (so each finalized window
reads one consistent snapshot). ``handle_knowledge_updated`` is invoked from the
``knowledge.updated`` consumer to re-fetch on demand without a restart (AC-8). A refresh failure
keeps the last-good snapshot (EH-6).
"""

from __future__ import annotations

from .clients import KnowledgeClient
from .config import FeatureConfig, ParamStore
from .logging_setup import get_logger

log = get_logger(__name__)


class ParamLoader:
    """Loads + hot-swaps DBSCAN params and feature config from the Knowledge Service."""

    def __init__(
        self,
        knowledge_client: KnowledgeClient,
        param_store: ParamStore,
        feature_config: FeatureConfig,
        metrics=None,
    ) -> None:
        self._client = knowledge_client
        self._params = param_store
        self._features = feature_config
        self._metrics = metrics

    def load(self) -> None:
        """Fetch + atomically swap params and feature config. Raises on failure (startup gate)."""
        params = self._client.fetch_model_params()
        features = self._client.fetch_feature_config()
        self._params.set(params)
        self._features.set(features)
        if self._metrics is not None:
            self._metrics.knowledge_refresh.inc()
        log.info(
            "knowledge_params_loaded",
            eps=params.eps,
            min_samples=params.min_samples,
            window_size=params.window_size_seconds,
            algorithm=params.algorithm,
            attribute_keys=list(features.attribute_keys),
            hop_distance_enabled=features.hop_distance_enabled,
        )

    def handle_knowledge_updated(self) -> bool:
        """Re-fetch on a ``knowledge.updated`` event; keep last-good on failure (EH-6).

        Returns ``True`` if the refresh succeeded, ``False`` if it failed (last-good retained).
        """
        try:
            self.load()
            return True
        except Exception as exc:  # noqa: BLE001 — keep last-good, never crash
            if self._metrics is not None:
                self._metrics.knowledge_refresh_failures.inc()
            log.warning("knowledge_refresh_failed", error=str(exc))
            return False
