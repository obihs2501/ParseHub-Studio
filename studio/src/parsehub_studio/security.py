from __future__ import annotations

import secrets

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, RedirectResponse


def install_security(app: FastAPI, session_token: str | None) -> None:
    @app.middleware("http")
    async def protect_local_service(request: Request, call_next):
        if request.url.hostname not in {"localhost", "127.0.0.1", "::1", "testserver"}:
            return JSONResponse({"detail": "不允许的主机地址"}, status_code=403)
        origin = request.headers.get("origin")
        expected_origin = str(request.base_url).rstrip("/")
        if origin and origin != expected_origin:
            return JSONResponse({"detail": "不允许跨站访问本地服务"}, status_code=403)
        if request.headers.get("sec-fetch-site") == "cross-site":
            return JSONResponse({"detail": "不允许跨站访问本地服务"}, status_code=403)
        cookie_name = f"parsehub_session_{request.url.port or 80}"
        if session_token:
            if request.url.path == "/desktop" and request.method == "GET":
                supplied = request.query_params.get("token", "")
                if not secrets.compare_digest(supplied, session_token):
                    return JSONResponse({"detail": "无效的桌面会话"}, status_code=401)
                response = RedirectResponse("/", status_code=303)
                response.set_cookie(cookie_name, session_token, httponly=True, samesite="strict")
                response.headers["Cache-Control"] = "no-store"
                response.headers["Referrer-Policy"] = "no-referrer"
                return response
            supplied = request.cookies.get(cookie_name, "")
            bearer = request.headers.get("authorization", "").removeprefix("Bearer ")
            if not (secrets.compare_digest(supplied, session_token) or secrets.compare_digest(bearer, session_token)):
                return JSONResponse({"detail": "桌面会话已失效，请重新启动应用"}, status_code=401)
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            "img-src 'self' https: http: data: blob:; media-src 'self' https: http: blob:; "
            "connect-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"
        )
        if request.url.path.startswith("/api/") and request.url.path != "/api/media":
            response.headers["Cache-Control"] = "no-store"
        return response
