import { http } from './client.js';

/** Endpoint catalogue (§7.2) — features call these, never axios directly (TR-008). */
const data = (p) => p.then((r) => r.data);

export const auth = {
  login: (body) => data(http.post('/auth/login', body)),
  register: (body) => data(http.post('/auth/register', body)),
  logout: () => http.post('/auth/logout'),
};

export const me = {
  get: () => data(http.get('/me')),
  update: (body) => data(http.patch('/me', body)),
  changePassword: (body) => http.post('/me/password', body),
};

export const projects = {
  list: (status = 'ACTIVE') => data(http.get('/projects', { params: { status } })),
  get: (key) => data(http.get(`/projects/${key}`)),
  create: (body) => data(http.post('/projects', body)),
  update: (key, body) => data(http.patch(`/projects/${key}`, body)),
  archive: (key) => data(http.post(`/projects/${key}/archive`)),
  unarchive: (key) => data(http.post(`/projects/${key}/unarchive`)),
  members: (key) => data(http.get(`/projects/${key}/members`)),
  addMember: (key, body) => data(http.post(`/projects/${key}/members`, body)),
  updateMember: (key, userId, body) => data(http.patch(`/projects/${key}/members/${userId}`, body)),
  removeMember: (key, userId) => http.delete(`/projects/${key}/members/${userId}`),
  labels: (key) => data(http.get(`/projects/${key}/labels`)),
  createLabel: (key, body) => data(http.post(`/projects/${key}/labels`, body)),
  updateLabel: (id, body) => data(http.patch(`/labels/${id}`, body)),
  deleteLabel: (id) => http.delete(`/labels/${id}`),
};

export const tasks = {
  list: (key, params) => data(http.get(`/projects/${key}/tasks`, { params })),
  get: (taskKey) => data(http.get(`/tasks/${taskKey}`)),
  create: (key, body, idempotencyKey) =>
    data(http.post(`/projects/${key}/tasks`, body, { headers: { 'Idempotency-Key': idempotencyKey } })),
  update: (taskKey, body) => data(http.patch(`/tasks/${taskKey}`, body)),
  setStatus: (taskKey, body) => data(http.post(`/tasks/${taskKey}/status`, body)),
  remove: (taskKey) => http.delete(`/tasks/${taskKey}`),
  setLabels: (taskKey, labelIds) => data(http.put(`/tasks/${taskKey}/labels`, { labelIds })),
  addSubtask: (taskKey, title) => data(http.post(`/tasks/${taskKey}/subtasks`, { title })),
  bulkSubtasks: (taskKey, titles) => data(http.post(`/tasks/${taskKey}/subtasks/bulk`, { titles })),
  updateSubtask: (id, body) => data(http.patch(`/subtasks/${id}`, body)),
  deleteSubtask: (id) => http.delete(`/subtasks/${id}`),
  reorderSubtasks: (taskKey, ids) => data(http.put(`/tasks/${taskKey}/subtasks/order`, { ids })),
  comments: (taskKey) => data(http.get(`/tasks/${taskKey}/comments`, { params: { size: 100 } })),
  addComment: (taskKey, body) => data(http.post(`/tasks/${taskKey}/comments`, { body })),
  editComment: (id, body) => data(http.patch(`/comments/${id}`, { body })),
  deleteComment: (id) => http.delete(`/comments/${id}`),
  addBlocker: (taskKey, blockerKey) => data(http.post(`/tasks/${taskKey}/blockers`, { blockerKey })),
  removeBlocker: (taskKey, blockerKey) => http.delete(`/tasks/${taskKey}/blockers/${blockerKey}`),
  refreshPrediction: (taskKey) => data(http.post(`/tasks/${taskKey}/prediction/refresh`)),
};

export const ai = {
  ask: (key, body) => data(http.post(`/projects/${key}/ask`, body, { timeout: 25_000 })),
  duplicates: (key, body, signal) =>
    data(http.post(`/projects/${key}/tasks/duplicate-check`, body, { signal })),
  split: (taskKey) => data(http.post(`/tasks/${taskKey}/split-suggestions`, null, { timeout: 45_000 })),
};

export const analytics = {
  workload: (key) => data(http.get(`/projects/${key}/analytics/workload`)),
  burndown: (key, params) => data(http.get(`/projects/${key}/analytics/burndown`, { params })),
  velocity: (key, weeks) => data(http.get(`/projects/${key}/analytics/velocity`, { params: { weeks } })),
};

export const apiKeys = {
  list: (all = false) => data(http.get('/api-keys', { params: { all } })),
  create: (body) => data(http.post('/api-keys', body)),
  revoke: (id) => http.delete(`/api-keys/${id}`),
};

export const admin = {
  users: () => data(http.get('/admin/users')),
  updateUser: (id, body) => data(http.patch(`/admin/users/${id}`, body)),
  models: () => data(http.get('/admin/ml/models')),
  train: () => data(http.post('/admin/ml/train')),
  reindex: () => data(http.post('/admin/ml/reindex')),
};
