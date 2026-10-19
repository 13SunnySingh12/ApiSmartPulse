import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useProject } from '../projects/ProjectLayout.jsx';

/** Mutations on one task: refresh its detail cache and the board lists afterwards. */
export function useTaskMutation(task, opts = {}) {
  const qc = useQueryClient();
  const project = useProject();
  return useMutation({
    ...opts,
    onSuccess: (data, vars, ctx) => {
      if (data?.key) qc.setQueryData(['task', task.key], data);
      else qc.invalidateQueries({ queryKey: ['task', task.key] });
      qc.invalidateQueries({ queryKey: ['tasks', project.key] });
      opts.onSuccess?.(data, vars, ctx);
    },
  });
}
