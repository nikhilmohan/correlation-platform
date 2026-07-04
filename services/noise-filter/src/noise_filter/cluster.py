"""DBSCAN storm/cascade detector (DA-1).

Dense regions = storms from a single propagating fault (one DBSCAN cluster -> one
``TransactionEvent``); sparse outliers get label ``-1`` (noise, dropped). DBSCAN is
deterministic for a fixed feature matrix + fixed params (reproducibility requirement).
``hdbscan`` is config-selectable but off by default.
"""

from __future__ import annotations

import numpy as np
from sklearn.cluster import DBSCAN

from .config import ModelParams

NOISE_LABEL = -1


class Clusterer:
    """Runs the configured clustering algorithm and returns one label per matrix row."""

    def __init__(self, metrics=None) -> None:
        self._metrics = metrics

    def label(self, matrix: np.ndarray, params: ModelParams) -> np.ndarray:
        """Return an array of cluster labels (>=0 cluster member; -1 noise) per row.

        Empty input returns an empty label array. ``algorithm`` selects DBSCAN (default) or
        hdbscan; an unknown algorithm falls back to DBSCAN.
        """
        if matrix.shape[0] == 0:
            return np.empty(0, dtype=int)

        algo = (params.algorithm or "dbscan").lower()
        if algo == "hdbscan":
            return self._hdbscan(matrix, params)
        return self._dbscan(matrix, params)

    def _dbscan(self, matrix: np.ndarray, params: ModelParams) -> np.ndarray:
        model = DBSCAN(eps=params.eps, min_samples=params.min_samples)
        return model.fit_predict(matrix).astype(int)

    def _hdbscan(self, matrix: np.ndarray, params: ModelParams) -> np.ndarray:
        try:
            import hdbscan  # type: ignore
        except ImportError:
            # hdbscan not installed in this image — fall back to DBSCAN (off-by-default path).
            return self._dbscan(matrix, params)
        model = hdbscan.HDBSCAN(min_cluster_size=max(params.min_samples, 2))
        return model.fit_predict(matrix).astype(int)
