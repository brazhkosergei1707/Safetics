import type { Shift } from '../../api/projects'

/** Готові графіки: 2×12, 3×8, 1×12, 1×8. */
export const SHIFT_PRESETS: Record<string, Shift[]> = {
  '2×12': [
    { code: 'day', name: 'Day', start: '07:00', durationMinutes: 720 },
    { code: 'night', name: 'Night', start: '19:00', durationMinutes: 720 },
  ],
  '3×8': [
    { code: 'a', name: 'A', start: '06:00', durationMinutes: 480 },
    { code: 'b', name: 'B', start: '14:00', durationMinutes: 480 },
    { code: 'c', name: 'C', start: '22:00', durationMinutes: 480 },
  ],
  '1×12': [{ code: 'day', name: 'Day', start: '07:00', durationMinutes: 720 }],
  '1×8': [{ code: 'day', name: 'Day', start: '08:00', durationMinutes: 480 }],
}
