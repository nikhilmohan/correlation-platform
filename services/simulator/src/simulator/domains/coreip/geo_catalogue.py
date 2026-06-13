"""Grounded geo-site catalogue — ≥10 distinct telco-PoP cities (fix B4, criterion 34).

A fixed catalogue of 12 distinct, grounded telco PoP cities, each with a real-ish
``{name, latitude, longitude, region}`` and distinct coordinates (no reused/fabricated
coords). ``SITE_COUNT=N`` selects the first N entries, so ``SITE_COUNT=10`` yields 10 distinct
grounded sites. A ``SITE_COUNT`` above the catalogue size fails fast in config validation.
"""

from __future__ import annotations

from simulator.engine.domain_pack import GeoSite

# 12 distinct grounded telco PoP cities (≥10 with headroom).
GEO_CATALOGUE: tuple[GeoSite, ...] = (
    GeoSite("LON-01", "London Docklands", 51.5033, -0.0195, "UK-South"),
    GeoSite("MAN-01", "Manchester Central", 53.4779, -2.2426, "UK-North"),
    GeoSite("AMS-01", "Amsterdam Zuidoost", 52.3105, 4.9447, "EU-West"),
    GeoSite("FRA-01", "Frankfurt am Main", 50.1109, 8.6821, "EU-Central"),
    GeoSite("PAR-01", "Paris Aubervilliers", 48.9145, 2.3819, "EU-West"),
    GeoSite("MAD-01", "Madrid Alcobendas", 40.5400, -3.6420, "EU-South"),
    GeoSite("MIL-01", "Milan Caldera", 45.4642, 9.1900, "EU-South"),
    GeoSite("STO-01", "Stockholm Kista", 59.4030, 17.9510, "EU-North"),
    GeoSite("DUB-01", "Dublin Citywest", 53.2870, -6.4290, "IE"),
    GeoSite("WAW-01", "Warsaw Wola", 52.2330, 20.9840, "EU-East"),
    GeoSite("ZRH-01", "Zurich Glattbrugg", 47.4290, 8.5640, "CH"),
    GeoSite("VIE-01", "Vienna Floridsdorf", 48.2570, 16.4000, "AT"),
)

CATALOGUE_SIZE: int = len(GEO_CATALOGUE)


def first_n_sites(n: int) -> tuple[GeoSite, ...]:
    """Return the first ``n`` distinct catalogue entries (caller validates ``n`` ≤ size)."""
    if n > CATALOGUE_SIZE:
        raise ValueError(
            f"SITE_COUNT={n} exceeds the grounded geo catalogue size {CATALOGUE_SIZE}"
        )
    return GEO_CATALOGUE[:n]
