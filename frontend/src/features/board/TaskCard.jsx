import { Link } from 'react-router';
import { useDraggable } from '@dnd-kit/core';
import { CalendarDays, CheckSquare, GripVertical, MoreHorizontal, MoveRight } from 'lucide-react';
import {
  Avatar,
  BlockedChip,
  LabelChip,
  PriorityChip,
  RiskBadge,
  statusLabel,
} from '../../components/ui/Chips.jsx';
import { Menu, MenuItem, MenuLabel } from '../../components/ui/Menu.jsx';
import { formatDate, hours, isOverdue } from '../../utils/format.js';
import { COLUMNS } from './useBoard.js';

/**
 * Board card (§4.6.4): clay without blur. The title is a stretched link to the detail; the drag
 * handle and the "Move to…" menu are separate buttons so both work by keyboard (DS-032).
 */
export function TaskCard({ task, projectKey, onMove, readOnly, column, index }) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, isDragging } = useDraggable({
    id: task.key,
    data: { task },
    disabled: readOnly,
  });
  const style = transform ? { transform: `translate3d(${transform.x}px, ${transform.y}px, 0)` } : undefined;
  const p = task.prediction;
  const extraLabels = task.labels.length - 2;
  const overdue = isOverdue(task.dueDate, task.status);

  return (
    <article
      ref={setNodeRef}
      style={style}
      className={`task-card clay ${isDragging ? 'task-card--dragging' : ''}`}
      aria-labelledby={`card-${task.key}`}
    >
      <div className="task-card__top">
        <span className="mono muted">{task.key}</span>
        <div className="task-card__tools">
          {!readOnly && (
            <>
              <button
                type="button"
                ref={setActivatorNodeRef}
                className="task-card__tool"
                aria-label={`Drag ${task.key}`}
                {...listeners}
                {...attributes}
              >
                <GripVertical aria-hidden="true" />
              </button>
              <Menu
                label={`Actions for ${task.key}`}
                trigger={
                  <button
                    type="button"
                    className="task-card__tool"
                    aria-label={`Move ${task.key} to another column`}
                  >
                    <MoreHorizontal aria-hidden="true" />
                  </button>
                }
              >
                <MenuLabel>Move to…</MenuLabel>
                {COLUMNS.filter((c) => c !== task.status).map((c) => (
                  <MenuItem key={c} icon={<MoveRight />} onSelect={() => onMove(task, c)}>
                    {statusLabel(c)}
                  </MenuItem>
                ))}
              </Menu>
            </>
          )}
        </div>
      </div>
      <h3 className="task-card__title" id={`card-${task.key}`}>
        <Link
          to={`/projects/${projectKey}/tasks/${task.key}`}
          className="task-card__link"
          data-card
          data-col={column}
          data-index={index}
        >
          {task.title}
        </Link>
      </h3>
      <div className="task-card__chips">
        {task.blocked && task.status !== 'DONE' && <BlockedChip />}
        <PriorityChip priority={task.priority} />
        {task.status !== 'DONE' && <RiskBadge prediction={p} />}
        {task.labels.slice(0, 2).map((l) => (
          <LabelChip key={l.id} label={l} />
        ))}
        {extraLabels > 0 && <span className="chip">+{extraLabels}</span>}
      </div>
      <div className="task-card__foot small">
        {task.assignee ? <Avatar name={task.assignee.name} /> : <span className="muted">Unassigned</span>}
        {task.dueDate && (
          <span className={overdue ? 'due due--overdue' : 'due'}>
            <CalendarDays aria-hidden="true" />
            {overdue && <span className="visually-hidden">Overdue,</span>}
            {formatDate(task.dueDate)}
          </span>
        )}
        {(task.estimateHours || (p?.status === 'OK' && p.predictedHours)) && (
          <span className="muted">
            {task.estimateHours ? hours(task.estimateHours) : 'No estimate'}
            {p?.status === 'OK' &&
              p.predictedHours != null &&
              ` · ${p.source === 'MODEL' ? 'AI' : 'Rule'} ${hours(p.predictedHours)}`}
          </span>
        )}
        {task.subtaskSummary.total > 0 && (
          <span className="muted">
            <CheckSquare aria-hidden="true" /> {task.subtaskSummary.done}/{task.subtaskSummary.total}
          </span>
        )}
      </div>
    </article>
  );
}
