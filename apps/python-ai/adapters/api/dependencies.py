"""内部 API 鉴权依赖。"""

from fastapi import Header, HTTPException

from config import settings


def require_internal_token(x_internal_token: str = Header(default="")) -> None:
    allowed = {
        token for token in (
            settings.internal_token_j2p_current,
            settings.internal_token_j2p_next,
        ) if token
    }
    if x_internal_token not in allowed:
        raise HTTPException(status_code=401, detail="invalid internal token")
