"""
app/logredact.py
로그에 비밀값이 새어 나가지 않게 가립니다.

<b>실제로 겪은 일</b> — FRED 호출이 느려 urllib3가 재시도 경고를 찍었는데,
그 경고에 **요청 URL 전체**가 들어 있었습니다.

    WARNING urllib3.connectionpool: Retrying (...) after connection broken by
    'ReadTimeoutError(...)': /fred/series/observations?series_id=WALCL
    &api_key=6a60c4dbe7774f0130eb4ed6a9aa26a4&file_type=json&...

API 키가 그대로 노출됩니다. `make logs`를 뜨는 사람, 로그를 붙여 넣는 곳,
로그 수집기 어디에나 남습니다.

우리 코드만 조심해서는 막을 수 없습니다. URL을 찍는 쪽이 서드파티
라이브러리이기 때문입니다. 그래서 **로깅 계층에서** 가립니다 — 어떤 코드가
찍든 한 곳에서 걸립니다.
"""
from __future__ import annotations

import logging
import re

# 이름만 봐도 비밀인 쿼리 파라미터들. 값이 무엇이든 가립니다.
_SECRET_PARAMS = (
    "api_key", "apikey", "auth_key", "authkey", "token", "access_token",
    "appkey", "app_key", "appsecret", "app_secret", "secret", "password",
)

_QUERY_PATTERN = re.compile(
    r"(?i)\b(" + "|".join(_SECRET_PARAMS) + r")=([^&\s\"'<>]+)"
)

# "Authorization: Bearer xxx" 같은 헤더 표기도 가립니다.
_BEARER_PATTERN = re.compile(r"(?i)(bearer\s+)([A-Za-z0-9._\-]{8,})")

_MASK = "***redacted***"


def redact(text: str) -> str:
    """문자열에서 비밀값으로 보이는 부분을 가립니다."""
    text = _QUERY_PATTERN.sub(lambda m: f"{m.group(1)}={_MASK}", text)
    return _BEARER_PATTERN.sub(lambda m: f"{m.group(1)}{_MASK}", text)


def _might_contain_secret(text: str) -> bool:
    """
    정규식을 돌릴 가치가 있는지 싸게 걸러 냅니다.

    로그 한 줄마다 정규식 두 개를 돌리는 것은 낭비입니다. 다만 이 검사가
    좁으면 진짜 비밀이 새 나가므로, 가릴 수 있는 형태는 모두 통과시킵니다.
    """
    return "=" in text or "earer" in text


class RedactingFilter(logging.Filter):
    """
    모든 로그 레코드를 지나가며 비밀값을 가립니다.

    메시지와 인자를 각각 손봅니다. urllib3처럼 %s 인자에 URL을 담는 경우가
    있어 둘 다 확인해야 합니다.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str) and _might_contain_secret(record.msg):
            record.msg = redact(record.msg)

        if record.args:
            if isinstance(record.args, dict):
                record.args = {
                    key: redact(value) if isinstance(value, str) else value
                    for key, value in record.args.items()
                }
            elif isinstance(record.args, tuple):
                record.args = tuple(
                    redact(value) if isinstance(value, str) else value
                    for value in record.args
                )
        return True


def install() -> None:
    """
    루트 핸들러 전부에 필터를 답니다.

    로거가 아니라 **핸들러**에 다는 이유: 필터는 로거 계층을 따라 전파되지
    않습니다. 핸들러에 달면 어느 로거에서 온 레코드든 출력 직전에 걸립니다.
    """
    log_filter = RedactingFilter()
    root = logging.getLogger()
    for handler in root.handlers:
        handler.addFilter(log_filter)
