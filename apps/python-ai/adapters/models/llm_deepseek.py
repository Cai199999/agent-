"""DeepSeek OpenAI 兼容流式客户端。

LLM 通过端口注入，业务层不直接依赖 SDK；替换模型只需替换本适配器。
"""

from collections.abc import AsyncIterator, Sequence

from openai import AsyncOpenAI

from config import settings
from domain.chat import LLMDelta


class DeepSeekChatClient:
    def __init__(self) -> None:
        self._client = AsyncOpenAI(
            api_key=settings.deepseek_api_key,
            base_url=settings.llm_base_url,
            timeout=settings.llm_timeout_seconds,
            max_retries=0,
        )
        self._model = settings.llm_model

    @property
    def model(self) -> str:
        return self._model

    async def stream(self, messages: Sequence[dict], tools: list[dict] | None = None) -> AsyncIterator[LLMDelta]:
        params = {
            "model": self._model,
            "messages": list(messages),
            "temperature": settings.llm_temperature,
            "max_tokens": settings.llm_max_output_tokens,
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        if tools:
            params["tools"] = tools
            params["tool_choice"] = "auto"

        stream = await self._client.chat.completions.create(**params)
        tool_buffers: dict[int, dict] = {}
        finish_reason = None
        async for chunk in stream:
            choices = getattr(chunk, "choices", None) or []
            if choices:
                choice = choices[0]
                delta = getattr(choice, "delta", None)
                content = getattr(delta, "content", None) if delta else None
                if content:
                    yield LLMDelta(content=content)
                for call in (getattr(delta, "tool_calls", None) or []) if delta else []:
                    index = getattr(call, "index", 0) or 0
                    buffer = tool_buffers.setdefault(index, {"id": "", "name": "", "arguments": ""})
                    if getattr(call, "id", None):
                        buffer["id"] = call.id
                    function = getattr(call, "function", None)
                    if function is not None:
                        if getattr(function, "name", None):
                            buffer["name"] = function.name
                        if getattr(function, "arguments", None):
                            buffer["arguments"] += function.arguments
                if getattr(choice, "finish_reason", None):
                    finish_reason = choice.finish_reason
            usage = getattr(chunk, "usage", None)
            if usage:
                yield LLMDelta(
                    input_tokens=usage.prompt_tokens or 0,
                    output_tokens=usage.completion_tokens or 0,
                )

        if tool_buffers:
            calls = [tool_buffers[index] for index in sorted(tool_buffers)]
            yield LLMDelta(tool_calls=calls, finish_reason=finish_reason)
        elif finish_reason:
            yield LLMDelta(finish_reason=finish_reason)

    async def complete_json(self, messages: Sequence[dict[str, str]]) -> str:
        response = await self._client.chat.completions.create(
            model=self._model,
            messages=list(messages),
            temperature=0,
            max_tokens=600,
            response_format={"type": "json_object"},
        )
        choices = getattr(response, "choices", None) or []
        if not choices:
            return "{}"
        content = getattr(choices[0].message, "content", None)
        return content or "{}"
