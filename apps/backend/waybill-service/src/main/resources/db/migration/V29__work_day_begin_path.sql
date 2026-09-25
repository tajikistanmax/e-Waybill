-- «Гашти ибтидоӣ» начала и конца смены (legacy begin_path_a / begin_path_b форм 1-АД, 1-А, 3-С):
-- селектор, какой нулевой пробег маршрута (А или Б) входит в общий пробег дня. Значения —
-- имена полей маршрута 'begin_path_a' / 'begin_path_b', как в legacy; NULL — не выбрано.
-- В боевой базе legacy у 1-АД: А+А — 61 245 листов, только А — 47 481, А+Б — 24 420 (за год).
ALTER TABLE work_day
    ADD COLUMN begin_path_a VARCHAR(16),
    ADD COLUMN begin_path_b VARCHAR(16);
