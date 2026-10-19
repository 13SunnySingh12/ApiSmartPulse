CREATE INDEX ix_embeddings_hnsw ON task_embeddings USING hnsw (embedding vector_cosine_ops);
