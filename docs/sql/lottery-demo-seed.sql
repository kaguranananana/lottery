-- 可选演示数据，仅供新库或独立测试环境。活动以 PASS 等待奖池预热。
SET NAMES utf8mb4;
USE lottery;
START TRANSACTION;

INSERT INTO award (award_id, award_type, award_name, award_content) VALUES
    ('1',1,'IMac','Code'), ('2',1,'iphone','Code'), ('3',1,'ipad','Code'),
    ('4',1,'AirPods','Code'), ('5',1,'Book','Code');

INSERT INTO draw_config (draw_config_id, draw_config_desc, grant_type) VALUES
    (10001,'男性用户抽奖配置',1), (10002,'女性年轻用户抽奖配置',1), (10003,'女性成熟用户抽奖配置',1);

INSERT INTO draw_config_prize (draw_config_id, award_id, award_count, award_surplus_count, award_rate) VALUES
    (10001,'1',10,10,0.05), (10001,'2',20,20,0.15), (10001,'3',50,50,0.20),
    (10001,'4',100,100,0.25), (10001,'5',500,500,0.35),
    (10002,'1',5,5,0.03), (10002,'2',15,15,0.12), (10002,'3',40,40,0.20),
    (10002,'4',80,80,0.25), (10002,'5',400,400,0.40),
    (10003,'1',5,5,0.03), (10003,'2',15,15,0.12), (10003,'3',40,40,0.20),
    (10003,'4',80,80,0.25), (10003,'5',400,400,0.40);

INSERT INTO activity (activity_id, activity_name, activity_desc, begin_date_time, end_date_time,
                      take_count, draw_config_id, state, creator) VALUES
    (100001,'男性专属活动-年轻版','面向年轻男性用户的促销活动','2025-01-01','2030-12-31 23:59:59',10,10001,4,'xiaofuge'),
    (100002,'男性专属活动-成熟版','面向成熟男性用户的促销活动','2025-01-01','2030-12-31 23:59:59',10,10001,4,'xiaofuge'),
    (100003,'女性专属活动-年轻版','面向年轻女性用户的促销活动','2025-01-01','2030-12-31 23:59:59',10,10002,4,'system'),
    (100004,'女性专属活动-成熟版','面向成熟女性用户的促销活动','2025-01-01','2030-12-31 23:59:59',10,10003,4,'system');

COMMIT;
