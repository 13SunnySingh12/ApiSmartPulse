import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { problem } from '../../api/client.js';
import { tasks } from '../../api/endpoints.js';
import { useToast } from '../../components/ui/Toast.jsx';
import { statusLabel } from '../../components/ui/Chips.jsx';

export const COLUMNS = ['TODO', 'IN_PROGRESS', 'DONE'];

/** One query per column so each shows its own total; open columns by due date, done by recency. */
export function useColumn(projectKey, status, filters) {
  const params = {
    status,
    size: 100,
    sort: status === 'DONE' ? 'updatedAt,desc' : 'dueDate,asc',
    ...(filters.assigneeId && { assigneeId: filters.assigneeId }),
    ...(filters.priorities.length && { priority: filters.priorities.join(',') }),
    ...(filters.highRisk && { risk: 'HIGH' }),
    ...(filters.label && { label: filters.label }),
    ...(filters.q.trim().length >= 2 && { q: filters.q.trim() }),
  };
  return useQuery({ queryKey: ['tasks', projectKey, params], queryFn: () => tasks.list(projectKey, params) });
}

/** Status change shared by drag-and-drop, the "Move to…" menu and the task detail. */
export function useMoveTask(projectKey) {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: ({ task, status, actualHours }) =>
      tasks.setStatus(task.key, { status, version: task.version, ...(actualHours ? { actualHours } : {}) }),
    onSuccess: (updated) => {
      toast.success(`${updated.key} moved to ${statusLabel(updated.status)}`);
      qc.setQueryData(['task', updated.key], updated);
    },
    onError: (e, { task }) => {
      const p = problem(e);
      if (p.code === 'TASK_BLOCKED') {
        const list = (p.data.blockers || []).map((b) => `${b.key} (${statusLabel(b.status)})`).join(', ');
        toast.error(`${task.key} is blocked by ${list}. Finish those first.`);
      } else if (p.code === 'CONFLICT_STALE') {
        toast.info(`${task.key} changed since you loaded the board. It's been refreshed.`);
      } else {
        toast.error(p.detail || 'Could not move the task.', { requestId: p.requestId });
      }
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['tasks', projectKey] });
      qc.invalidateQueries({ queryKey: ['projects'] });
    },
  });
}
