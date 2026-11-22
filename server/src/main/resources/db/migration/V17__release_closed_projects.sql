-- Закриття проєкту тепер звільняє людей і обладнання (AssignmentService.closeProject): призначення,
-- що почались раніше дня закриття, закінчуються напередодні, пізніші — видаляються. Тут — те саме
-- для проєктів, закритих раніше.

delete from personnel_assignment pa
    using project p
where p.id = pa.project_id
  and p.status = 'closed'
  and pa.start_date >= p.end_date;

update personnel_assignment pa
set end_date   = p.end_date - 1,
    updated_at = now()
from project p
where p.id = pa.project_id
  and p.status = 'closed'
  and pa.start_date < p.end_date
  and (pa.end_date is null or pa.end_date >= p.end_date);

delete from equipment_assignment ea
    using project p
where p.id = ea.project_id
  and p.status = 'closed'
  and ea.start_date >= p.end_date;

update equipment_assignment ea
set end_date   = p.end_date - 1,
    updated_at = now()
from project p
where p.id = ea.project_id
  and p.status = 'closed'
  and ea.start_date < p.end_date
  and (ea.end_date is null or ea.end_date >= p.end_date);
