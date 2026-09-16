"""
app/yfcache.py
yfinance 캐시 폴더를 **스레드가 뜨기 전에** 미리 만들어 둡니다.

yfinance는 캐시 폴더를 이렇게 만듭니다(yfinance/cache.py).

    if not os.path.isdir(cls._cache_dir):
        try:
            os.makedirs(cls._cache_dir)      # ← exist_ok=True가 없습니다
        except OSError as err:
            raise _TzCacheException(f"Error creating TzCache folder: …")

`isdir` 확인과 `makedirs` 사이에 틈이 있습니다. 우리는 티커를 병렬로 받기
때문에 여러 스레드가 동시에 이 지점을 지나고, 먼저 만든 쪽을 뺀 나머지가
FileExistsError를 맞습니다. 실제 로그에 이렇게 세 번 찍혔습니다.

    yfinance: Failed to create TzCache, reason: Error creating TzCache folder:
    '/root/.cache/py-yfinance' reason: [Errno 17] File exists: …
    TzCache will not be used.

경고로 끝나는 문제가 아닙니다. **"TzCache will not be used"** 는 이후 모든
티커의 시간대를 매번 네트워크로 다시 묻는다는 뜻이라, 수집이 그만큼 느려지고
Yahoo 호출도 늘어납니다.

폴더를 미리 만들어 두면 `isdir`이 참이 되어 경쟁 구간을 아예 지나지 않습니다.
"""
from __future__ import annotations

import logging
import os
from pathlib import Path

logger = logging.getLogger(__name__)

_DEFAULT_DIR = "/tmp/py-yfinance"
_configured = False


def configure() -> None:
    """
    캐시 위치를 정하고 폴더를 미리 만듭니다. 여러 번 불러도 안전합니다.

    위치는 YFINANCE_CACHE_DIR로 바꿀 수 있습니다. 기본값을 홈 디렉터리 대신
    /tmp로 둔 이유: 컨테이너에서 홈이 읽기 전용이거나 없을 수 있고, 캐시는
    지워져도 되는 값이기 때문입니다.
    """
    global _configured
    if _configured:
        return

    cache_dir = os.environ.get("YFINANCE_CACHE_DIR", _DEFAULT_DIR)

    try:
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        # 폴더를 못 만들면 캐시 없이 도는 것뿐입니다. 수집을 막지는 않습니다.
        logger.warning("yfinance 캐시 폴더를 만들지 못했습니다 (%s): %s", cache_dir, exc)
        _configured = True
        return

    try:
        import yfinance as yf

        yf.set_tz_cache_location(cache_dir)
    except Exception as exc:  # noqa: BLE001
        logger.warning("yfinance 캐시 위치를 지정하지 못했습니다: %s", exc)
    else:
        logger.debug("yfinance 캐시 위치: %s", cache_dir)

    _configured = True
