# Local/CI image only — never pushed to a registry.
FROM python:3.12-slim
ARG INSTALL_ML_EXTRAS=true
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1 \
    HF_HOME=/cache/hf MODELS_DIR=/models
WORKDIR /app
COPY requirements.txt requirements-ml.txt ./
RUN pip install --require-hashes -r requirements.txt \
 && if [ "$INSTALL_ML_EXTRAS" = "true" ]; then \
      pip install --require-hashes --extra-index-url https://download.pytorch.org/whl/cpu -r requirements-ml.txt; \
    fi
RUN useradd --system --create-home --uid 10001 app && mkdir -p /models /cache/hf && chown app:app /models /cache/hf
COPY app ./app
COPY scripts ./scripts
USER app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
