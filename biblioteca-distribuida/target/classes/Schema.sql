-- =====================================================================
-- 1. Borrar tablas si existen (para pruebas limpias)
-- =====================================================================
DROP TABLE IF EXISTS loans CASCADE;
DROP TABLE IF EXISTS branch_inventory CASCADE;
DROP TABLE IF EXISTS books CASCADE;
DROP TABLE IF EXISTS branches CASCADE;

-- =====================================================================
-- 2. Tablas del sistema
-- =====================================================================

-- Sedes de la biblioteca
CREATE TABLE branches (
  id   TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

-- Catálogo global de libros (título, autor, total por todas las sedes)
CREATE TABLE books (
  code         TEXT PRIMARY KEY,
  title        TEXT NOT NULL,
  author       TEXT,
  total_copies INT NOT NULL
);

-- Inventario por sede (cuántos ejemplares maneja cada sede)
CREATE TABLE branch_inventory (
  branch_id        TEXT REFERENCES branches(id),
  book_code        TEXT REFERENCES books(code),
  total_copies     INT NOT NULL,
  available_copies INT NOT NULL,
  PRIMARY KEY (branch_id, book_code)
);

-- Tabla de préstamos
CREATE TABLE loans (
  loan_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- ID autoincremental
  user_id   TEXT NOT NULL,
  book_code TEXT NOT NULL REFERENCES books(code),
  branch_id TEXT NOT NULL REFERENCES branches(id),
  start_date DATE NOT NULL,
  due_date   DATE NOT NULL,
  renewals   INT NOT NULL DEFAULT 0,                          -- número de renovaciones
  status     TEXT NOT NULL CHECK (status IN ('ACTIVE','RETURNED','EXPIRED'))
);

-- Índice para acelerar búsquedas de préstamos activos
CREATE INDEX idx_loans_active
  ON loans(user_id, book_code, branch_id)
  WHERE status = 'ACTIVE';

-- =====================================================================
-- 3. Crear sedes de la biblioteca
-- =====================================================================
INSERT INTO branches (id, name) VALUES
  ('S1', 'Sede 1'),
  ('S2', 'Sede 2');

-- =====================================================================
-- 4. Insertar catálogo de 1000 libros
--     Libros generados automáticamente con generate_series
-- =====================================================================
INSERT INTO books (code, title, author, total_copies)
SELECT
  'BK-' || LPAD(gs::text, 4, '0'),
  'Libro ' || gs,
  'Autor ' || ((gs % 50) + 1),
  0
FROM generate_series(1, 1000) AS gs;

-- =====================================================================
-- 5. Población del inventario por sede
-- =====================================================================

-- 5.1 Libros 1–900 disponibles en ambas sedes
INSERT INTO branch_inventory (branch_id, book_code, total_copies, available_copies)
SELECT
  'S1',
  'BK-' || LPAD(gs::text, 4, '0'),
  20, 20
FROM generate_series(1, 900) AS gs;

INSERT INTO branch_inventory (branch_id, book_code, total_copies, available_copies)
SELECT
  'S2',
  'BK-' || LPAD(gs::text, 4, '0'),
  20, 20
FROM generate_series(1, 900) AS gs;

-- 5.2 Libros 901–950 solo en S1
INSERT INTO branch_inventory (branch_id, book_code, total_copies, available_copies)
SELECT
  'S1',
  'BK-' || LPAD(gs::text, 4, '0'),
  20, 20
FROM generate_series(901, 950) AS gs;

-- 5.3 Libros 951–1000 solo en S2
INSERT INTO branch_inventory (branch_id, book_code, total_copies, available_copies)
SELECT
  'S2',
  'BK-' || LPAD(gs::text, 4, '0'),
  20, 20
FROM generate_series(951, 1000) AS gs;

-- 5.4 Actualizar en books el total de copias sumando inventarios por sede
UPDATE books b
SET total_copies = inv.sum_copies
FROM (
  SELECT book_code, SUM(total_copies) AS sum_copies
  FROM branch_inventory
  GROUP BY book_code
) AS inv
WHERE b.code = inv.book_code;

-- =====================================================================
-- 6. Crear préstamos iniciales para simular carga
-- =====================================================================

-- 6.1 50 préstamos en la sede S1 (libros 1–50)
INSERT INTO loans (user_id, book_code, branch_id, start_date, due_date, renewals, status)
SELECT
  'uS1_' || LPAD(gs::text, 3, '0'),
  'BK-' || LPAD(gs::text, 4, '0'),
  'S1',
  CURRENT_DATE,
  CURRENT_DATE + INTERVAL '14 day',
  0,
  'ACTIVE'
FROM generate_series(1, 50) AS gs;

-- 6.2 150 préstamos en S2 (libros 51–200)
INSERT INTO loans (user_id, book_code, branch_id, start_date, due_date, renewals, status)
SELECT
  'uS2_' || LPAD(gs::text, 3, '0'),
  'BK-' || LPAD(gs::text, 4, '0'),
  'S2',
  CURRENT_DATE,
  CURRENT_DATE + INTERVAL '14 day',
  0,
  'ACTIVE'
FROM generate_series(51, 200) AS gs;

-- =====================================================================
-- 7. Ajustar inventario disponible según los préstamos existentes
-- =====================================================================
UPDATE branch_inventory bi
SET available_copies = GREATEST(0, bi.available_copies - COALESCE(lo.cnt, 0))
FROM (
  SELECT branch_id, book_code, COUNT(*) AS cnt
  FROM loans
  WHERE status = 'ACTIVE'
  GROUP BY branch_id, book_code
) AS lo
WHERE bi.branch_id = lo.branch_id
  AND bi.book_code = lo.book_code;