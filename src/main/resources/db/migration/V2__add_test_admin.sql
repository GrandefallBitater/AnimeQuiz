INSERT INTO users (username, password, enabled)
VALUES (
           'admin',
           '$2a$12$6TEW6IB3S9H.q4XQuXmdy.67yEPDhpuvCvChcRiJsWsp/ltRl0qru',
           true
       );

-- noinspection SqlInsertValues
INSERT INTO authorities (username, authority)
VALUES (
           'admin',
           'ROLE_ADMIN'
       );