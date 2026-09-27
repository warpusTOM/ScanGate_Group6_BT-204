# Project Proposal

## Title

**IDCheck: A Student ID Verification and Email Notification System for
the College of St. Catherine of Quezon City**

Proponents: Group 6 (BT-204)
Course: Object-Oriented Programming
Date: September 2026

---

## 1. Introduction

At school events and gate checks, staff still verify students by looking
at the physical ID and writing names in a logbook. It is slow when a line
forms, the handwriting is hard to read later, and there is no way to
notify a student that their ID was used. We want a small system where the
guard or instructor types or scans the student number and right away sees
if the ID is valid.

IDCheck does this. It reads the student number, checks it against the
school's student list stored in a local database, shows the student's
name and course on the spot, and saves the scan with the date and time.
If email is turned on, the student also gets a Gmail notice saying their
ID was scanned. The whole thing runs offline on one laptop. Email is the
only part that needs internet, and the system works fine without it.

## 2. Statement of the Problem

- Manual ID checking is slow during peak hours.
- Paper logbooks are hard to search and easy to lose.
- There is no record that a student can check later, and no notification
  when their ID is used.
- Existing school portals need a server and internet, which the gate or
  event venue may not have.

## 3. Objectives

General: build an offline ID verification system that shows OOP in Python.

Specific:

1. Store the student list in a local SQLite database.
2. Verify a student number in one step and show the result clearly
   (green for registered, red for not registered).
3. Log every scan with date, time, and an optional note.
4. Import the student list from a CSV file, even when the column names
   are not exact (example: "Student No", "ID", or "LRN").
5. Optionally email the student through Gmail when their ID is scanned.
6. Keep the look consistent with the school portal so it feels familiar.

## 4. Scope and Limitations

In scope: single laptop, local database, CSV import, scan log, optional
Gmail notice, portal-style web page that runs on localhost.

Out of scope: barcode hardware drivers (any USB scanner works since it
types like a keyboard), face recognition, SMS, online sync, multi-user
accounts.

## 5. Algorithm

### 5.1 Main scan loop

```
1. Start the program and open the database (idcheck.db).
2. Wait for input in the scan box.
3. Read the student number.
4. Look it up in the students table.
5. If there is no match:
     show "NOT REGISTERED" in red, then go back to step 2.
6. If there is a match:
     show the student card (name, number, course, year and section, gmail).
7. Save one row in scan_logs (student number, timestamp, note).
8. If email is enabled and the student has a gmail on file:
     try to send the notice through smtp.gmail.com.
     If the send fails, keep the scan and mark it "not sent".
9. Go back to step 2 for the next student.
```

### 5.2 CSV import

```
1. Open the chosen CSV file.
2. Read the header row. Match each column to a field using the alias
   list (example: "Student No", "ID", and "LRN" all map to student_id).
3. If student_id or full_name has no matching column, stop and say
   which one is missing.
4. For each row: skip blank lines, build a Student, and validate it
   (blank names and gmails without "@" are rejected with the row number).
5. If the student number already exists, update that record.
   Otherwise insert a new one.
6. Report how many were added and how many were updated.
```

### 5.3 Flowchart

```
            +------------------+
            |      START       |
            +--------+---------+
                     v
            +------------------+
            |  open database   |
            +--------+---------+
                     v
            +------------------+<------------------+
            |  input / scan ID |                   |
            +--------+---------+                   |
                     v                             |
               +-----------+     NO     +-----------------------+
               | ID found? |----------->| show NOT REGISTERED   |
               +-----+-----+            +----------+------------+
                     | YES                         |
                     v                             |
            +------------------+                   |
            | show student card|                   |
            +--------+---------+                   |
                     v                             |
            +------------------+                   |
            | save scan log    |                   |
            +--------+---------+                   |
                     v                             |
               +-----------+      NO               |
               | email on? |-----------+           |
               +-----+-----+           |           |
                     | YES             |           |
                     v                 |           |
            +------------------+       |           |
            | send gmail notice|       |           |
            +--------+---------+       |           |
                     v                 v           |
            +-----------------------------+      |
            |  ready for next student     |------+
            +-----------------------------+
```

## 6. OOP design

| Concept | Where we used it |
|---|---|
| Encapsulation | `Database` hides every SQL statement; `GmailNotifier` hides the app password |
| Inheritance | `GmailNotifier` and `NullNotifier` both extend the `Notifier` base class |
| Polymorphism | the system calls `notifier.send(...)` without caring if it is Gmail or the offline stub |
| Abstraction | `Notifier` is an abstract class with an abstract `send()` method |
| Composition | `IDCheckSystem` is composed of a `Database` and a `Notifier` |
| Facade | `IDCheckSystem` is the single entry point for the web app and the terminal version |

## 7. Tools

- Python 3.11, Flask for the local page, SQLite for storage
- HTML/CSS/JS hand-written to match the school portal look
- PyInstaller for the exe, unittest for testing

## 8. Expected output

1. A portal-style page at 127.0.0.1 where scanning an ID instantly shows
   verified or not registered.
2. A recent scans table that updates after every scan.
3. CSV import that accepts the registrar's export as-is.
4. Optional Gmail notices to students.
5. A portable exe that runs on any Windows laptop without installing
   anything.
