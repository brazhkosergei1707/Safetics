-- Профіль e2e: лише суперадмін платформи. Пароль: safetics-dev
insert into app_user (id, phone, full_name, password_hash, is_platform_admin)
values ('e0000000-0000-4000-8000-0000000000a0', '+380500000000', 'E2E Platform Admin',
        '{bcrypt}$2b$10$r7UeoPbrPap0uX2LsjBhGuFZ1lyOPUxk3ppxnBja4wWK3JwMU/7Ma', true)
on conflict (phone) do update set is_platform_admin = true;
