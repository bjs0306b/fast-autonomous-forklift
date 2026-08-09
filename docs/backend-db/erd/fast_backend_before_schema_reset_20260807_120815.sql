-- MySQL dump 10.13  Distrib 8.0.46, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: fast_backend
-- ------------------------------------------------------
-- Server version	8.0.46

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `fast_backend`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `fast_backend` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `fast_backend`;

--
-- Table structure for table `ai_cargo_analysis`
--

DROP TABLE IF EXISTS `ai_cargo_analysis`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_cargo_analysis` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `analysis_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `schema_version` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `vehicle_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `cargo_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `distance_cm` double DEFAULT NULL,
  `distance_std_cm` double DEFAULT NULL,
  `width_cm` double DEFAULT NULL,
  `height_cm` double DEFAULT NULL,
  `depth_cm` double DEFAULT NULL,
  `volume_cm3` double DEFAULT NULL,
  `dimension_scale` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `load_direction` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `load_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ratio_horizontal` double DEFAULT NULL,
  `ratio_vertical` double DEFAULT NULL,
  `message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `captured_at` datetime DEFAULT NULL,
  `processed_at` datetime NOT NULL,
  `received_at` datetime NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_cargo_analysis_analysis_id` (`analysis_id`),
  KEY `idx_ai_cargo_analysis_cargo_processed` (`cargo_id`,`processed_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ai_cargo_analysis`
--

LOCK TABLES `ai_cargo_analysis` WRITE;
/*!40000 ALTER TABLE `ai_cargo_analysis` DISABLE KEYS */;
/*!40000 ALTER TABLE `ai_cargo_analysis` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `ai_cargo_detection_box`
--

DROP TABLE IF EXISTS `ai_cargo_detection_box`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_cargo_detection_box` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `analysis_id` bigint NOT NULL,
  `class_name` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `confidence` double DEFAULT NULL,
  `bbox_x` int NOT NULL,
  `bbox_y` int NOT NULL,
  `bbox_width` int NOT NULL,
  `bbox_height` int NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_detection_box_analysis` (`analysis_id`),
  CONSTRAINT `fk_detection_box_analysis` FOREIGN KEY (`analysis_id`) REFERENCES `ai_cargo_analysis` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `ai_cargo_detection_box`
--

LOCK TABLES `ai_cargo_detection_box` WRITE;
/*!40000 ALTER TABLE `ai_cargo_detection_box` DISABLE KEYS */;
/*!40000 ALTER TABLE `ai_cargo_detection_box` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `cargo`
--

DROP TABLE IF EXISTS `cargo`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `cargo` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `cargo_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `width` double DEFAULT NULL,
  `length` double DEFAULT NULL,
  `height` double DEFAULT NULL,
  `volume` double DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cargo_cargo_id` (`cargo_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `cargo`
--

LOCK TABLES `cargo` WRITE;
/*!40000 ALTER TABLE `cargo` DISABLE KEYS */;
INSERT INTO `cargo` VALUES (1,'TEST-AI-20260731',0.18,0.27,0.15,0.0072900000000000005,'2026-07-31 12:48:02','2026-07-31 12:48:02.000000'),(2,'CARGO-001',NULL,NULL,NULL,NULL,'2026-08-04 15:31:50','2026-08-04 15:31:50.406883');
/*!40000 ALTER TABLE `cargo` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `embedded_error_history`
--

DROP TABLE IF EXISTS `embedded_error_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `embedded_error_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `forklift_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `error_code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `error_source` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `severity` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `occurred_at` datetime NOT NULL,
  `received_at` datetime NOT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_embedded_error_history_forklift_occurred` (`forklift_id`,`occurred_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `embedded_error_history`
--

LOCK TABLES `embedded_error_history` WRITE;
/*!40000 ALTER TABLE `embedded_error_history` DISABLE KEYS */;
/*!40000 ALTER TABLE `embedded_error_history` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `embedded_vehicle_command`
--

DROP TABLE IF EXISTS `embedded_vehicle_command`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `embedded_vehicle_command` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `command_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `forklift_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `command` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `target_system` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `command_category` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload_json` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `reason` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `issued_at` datetime NOT NULL,
  `published_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `error_code` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `result_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `stopped_actions` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `emergency_stop_applied` tinyint(1) DEFAULT NULL,
  `requires_reset` tinyint(1) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_embedded_vehicle_command_command_id` (`command_id`),
  KEY `idx_embedded_vehicle_command_forklift_issued` (`forklift_id`,`issued_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `embedded_vehicle_command`
--

LOCK TABLES `embedded_vehicle_command` WRITE;
/*!40000 ALTER TABLE `embedded_vehicle_command` DISABLE KEYS */;
/*!40000 ALTER TABLE `embedded_vehicle_command` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `pallet`
--

DROP TABLE IF EXISTS `pallet`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `pallet` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `pallet_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cargo_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `pickup_x` double DEFAULT NULL,
  `pickup_y` double DEFAULT NULL,
  `pickup_heading` double DEFAULT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pallet_pallet_id` (`pallet_id`),
  KEY `fk_pallet_cargo` (`cargo_id`),
  CONSTRAINT `fk_pallet_cargo` FOREIGN KEY (`cargo_id`) REFERENCES `cargo` (`cargo_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `pallet`
--

LOCK TABLES `pallet` WRITE;
/*!40000 ALTER TABLE `pallet` DISABLE KEYS */;
/*!40000 ALTER TABLE `pallet` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `rack`
--

DROP TABLE IF EXISTS `rack`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `rack` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rack_code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `rack_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `position_x` double DEFAULT NULL,
  `position_y` double DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rack_rack_code` (`rack_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `rack`
--

LOCK TABLES `rack` WRITE;
/*!40000 ALTER TABLE `rack` DISABLE KEYS */;
/*!40000 ALTER TABLE `rack` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `rack_level`
--

DROP TABLE IF EXISTS `rack_level`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `rack_level` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `rack_id` bigint NOT NULL,
  `level_number` int NOT NULL,
  `clear_width` double NOT NULL,
  `clear_length` double NOT NULL,
  `clear_height` double NOT NULL,
  `fork_height` double DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rack_level_rack_level` (`rack_id`,`level_number`),
  CONSTRAINT `fk_rack_level_rack` FOREIGN KEY (`rack_id`) REFERENCES `rack` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `rack_level`
--

LOCK TABLES `rack_level` WRITE;
/*!40000 ALTER TABLE `rack_level` DISABLE KEYS */;
/*!40000 ALTER TABLE `rack_level` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `station_measurement`
--

DROP TABLE IF EXISTS `station_measurement`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `station_measurement` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `measurement_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `session_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '측정 결과가 속한 세션',
  `cargo_height` double DEFAULT NULL COMMENT '팔레트를 제외한 화물 높이(meter)',
  `cargo_width` double DEFAULT NULL COMMENT '?? ?(m). ??? ? NULL',
  `frame_width` int DEFAULT NULL COMMENT 'boxes ?? ?? ?? ?? ???',
  `frame_height` int DEFAULT NULL COMMENT 'boxes ?? ?? ?? ?? ???',
  `boxes_json` json DEFAULT NULL COMMENT '?? ??? [x1,y1,x2,y2]?score',
  `overhang_ratio` double DEFAULT NULL COMMENT '팔레트 기준 화물 돌출 비율(무차원)',
  `station_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `schema_version` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `measured_at_utc` datetime DEFAULT NULL,
  `measured_at_offset_minutes` int DEFAULT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `box_count` int DEFAULT NULL,
  `pallet_bbox_x` int DEFAULT NULL,
  `pallet_bbox_y` int DEFAULT NULL,
  `pallet_bbox_width` int DEFAULT NULL,
  `pallet_bbox_height` int DEFAULT NULL,
  `pallet_score` double DEFAULT NULL,
  `front_cm` double DEFAULT NULL,
  `distance_std_cm` double DEFAULT NULL,
  `frames_used` int DEFAULT NULL,
  `height_cm` double DEFAULT NULL,
  `width_cm` double DEFAULT NULL,
  `depth_cm` double DEFAULT NULL,
  `miniature_scale` int DEFAULT NULL,
  `miniature_height_mm` double DEFAULT NULL,
  `miniature_width_mm` double DEFAULT NULL,
  `eccentric` tinyint(1) DEFAULT NULL,
  `load_direction` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `ratio_x` double DEFAULT NULL,
  `ratio_y` double DEFAULT NULL,
  `magnitude` double DEFAULT NULL,
  `threshold` double DEFAULT NULL,
  `load_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `tipping_assessable` tinyint(1) DEFAULT NULL,
  `tipping_level` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `tipping_static_stable` tinyint(1) DEFAULT NULL,
  `tipping_support_offset` double DEFAULT NULL,
  `tipping_margin` double DEFAULT NULL,
  `tipping_direction` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `tipping_aspect_ratio` double DEFAULT NULL,
  `tipping_overhang` double DEFAULT NULL,
  `tipping_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `received_at` datetime DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_station_measurement_measurement_id` (`measurement_id`),
  UNIQUE KEY `uk_station_measurement_session` (`session_id`),
  KEY `idx_station_measurement_station_measured` (`station_id`,`measured_at_utc` DESC),
  CONSTRAINT `fk_station_measurement_session` FOREIGN KEY (`session_id`) REFERENCES `station_session` (`session_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_station_measurement_cargo_height` CHECK (((`cargo_height` is null) or (`cargo_height` > 0))),
  CONSTRAINT `chk_station_measurement_overhang_ratio` CHECK (((`overhang_ratio` is null) or (`overhang_ratio` >= 0)))
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `station_measurement`
--

LOCK TABLES `station_measurement` WRITE;
/*!40000 ALTER TABLE `station_measurement` DISABLE KEYS */;
INSERT INTO `station_measurement` VALUES (1,'TEST-AI-20260731-roundtrip-1','7a9a34b6-afa5-44e8-b626-800d4010c3b8',0.152,NULL,NULL,NULL,NULL,0.12,NULL,NULL,'2026-07-31 04:00:00',540,'OK',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'SAFE',NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-31 12:48:02','2026-07-31 12:48:02.000000'),(2,'station-1-20260804-153151-0001','f77f2f5a-464e-4680-bf04-612be4902f46',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'NO_DETECTION',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-08-04 15:31:50.846964');
/*!40000 ALTER TABLE `station_measurement` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `station_measurement_box`
--

DROP TABLE IF EXISTS `station_measurement_box`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `station_measurement_box` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `station_measurement_id` bigint NOT NULL,
  `box_order` int NOT NULL,
  `bbox_x` int DEFAULT NULL,
  `bbox_y` int DEFAULT NULL,
  `bbox_width` int DEFAULT NULL,
  `bbox_height` int DEFAULT NULL,
  `score` double DEFAULT NULL,
  `created_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `fk_station_measurement_box_measurement` (`station_measurement_id`),
  CONSTRAINT `fk_station_measurement_box_measurement` FOREIGN KEY (`station_measurement_id`) REFERENCES `station_measurement` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `station_measurement_box`
--

LOCK TABLES `station_measurement_box` WRITE;
/*!40000 ALTER TABLE `station_measurement_box` DISABLE KEYS */;
/*!40000 ALTER TABLE `station_measurement_box` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `station_session`
--

DROP TABLE IF EXISTS `station_session`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `station_session` (
  `session_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '측정 세션 고유 식별자',
  `cargo_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '측정 대상 화물 식별자',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '세션 시작 시각',
  PRIMARY KEY (`session_id`),
  KEY `fk_station_session_cargo` (`cargo_id`),
  CONSTRAINT `fk_station_session_cargo` FOREIGN KEY (`cargo_id`) REFERENCES `cargo` (`cargo_id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `station_session`
--

LOCK TABLES `station_session` WRITE;
/*!40000 ALTER TABLE `station_session` DISABLE KEYS */;
INSERT INTO `station_session` VALUES ('7a9a34b6-afa5-44e8-b626-800d4010c3b8','TEST-AI-20260731','2026-07-31 12:48:01.884903'),('f77f2f5a-464e-4680-bf04-612be4902f46','CARGO-001','2026-08-04 15:31:50.408865');
/*!40000 ALTER TABLE `station_session` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `station_state`
--

DROP TABLE IF EXISTS `station_state`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `station_state` (
  `singleton_id` int NOT NULL COMMENT '단일 스테이션 행 고정값',
  `active_session_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '현재 점유 중인 측정 세션. NULL 이면 유휴',
  `acquired_at` datetime(6) DEFAULT NULL COMMENT '측정 설비 점유 시작 시각',
  PRIMARY KEY (`singleton_id`),
  KEY `fk_station_state_active_session` (`active_session_id`),
  CONSTRAINT `fk_station_state_active_session` FOREIGN KEY (`active_session_id`) REFERENCES `station_session` (`session_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_station_state_singleton` CHECK ((`singleton_id` = 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `station_state`
--

LOCK TABLES `station_state` WRITE;
/*!40000 ALTER TABLE `station_state` DISABLE KEYS */;
INSERT INTO `station_state` VALUES (1,NULL,NULL);
/*!40000 ALTER TABLE `station_state` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `storage_slot`
--

DROP TABLE IF EXISTS `storage_slot`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `storage_slot` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `slot_code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `rack_level_id` bigint DEFAULT NULL COMMENT '(구 컬럼) 현재 코드 미사용',
  `width` double DEFAULT NULL,
  `length` double DEFAULT NULL,
  `height` double DEFAULT NULL,
  `destination_x` double DEFAULT NULL,
  `destination_y` double DEFAULT NULL,
  `destination_heading` double DEFAULT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'EMPTY',
  `reserved_task_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `stored_cargo_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  `usable_height` double DEFAULT NULL COMMENT '수직 가용 높이(m)',
  `fork_height` double DEFAULT NULL COMMENT '목표 포크 높이(m)',
  `usable_width` double DEFAULT NULL COMMENT '수평 가용 폭(m). NULL 이면 폭 제약 없음',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_storage_slot_slot_code` (`slot_code`),
  KEY `idx_storage_slot_status` (`status`),
  KEY `idx_storage_slot_rack_level` (`rack_level_id`),
  KEY `idx_storage_slot_reserved_task` (`reserved_task_id`),
  CONSTRAINT `fk_storage_slot_rack_level` FOREIGN KEY (`rack_level_id`) REFERENCES `rack_level` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=197 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `storage_slot`
--

LOCK TABLES `storage_slot` WRITE;
/*!40000 ALTER TABLE `storage_slot` DISABLE KEYS */;
INSERT INTO `storage_slot` VALUES (1,'SLOT-01',NULL,NULL,NULL,NULL,2,1,180,'EMPTY',NULL,NULL,'2026-08-03 12:15:13.747303','2026-08-03 12:15:13.747303',1,0.15,NULL),(2,'SLOT-02',NULL,NULL,NULL,NULL,2,2,180,'EMPTY',NULL,NULL,'2026-08-03 12:15:13.747303','2026-08-03 12:15:13.747303',1.5,0.65,NULL),(125,'A001',NULL,NULL,NULL,NULL,5,9.3,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(126,'A002',NULL,NULL,NULL,NULL,5,10.5,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(127,'A003',NULL,NULL,NULL,NULL,5,11.8,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(128,'A004',NULL,NULL,NULL,NULL,5,13.2,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(129,'A005',NULL,NULL,NULL,NULL,5,14.5,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(130,'A006',NULL,NULL,NULL,NULL,5,15.8,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(131,'A007',NULL,NULL,NULL,NULL,5,17.2,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(132,'A008',NULL,NULL,NULL,NULL,5,18.5,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(133,'A009',NULL,NULL,NULL,NULL,5,19.7,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(134,'A010',NULL,NULL,NULL,NULL,5,21.2,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(135,'A011',NULL,NULL,NULL,NULL,5,22.5,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(136,'A012',NULL,NULL,NULL,NULL,5,23.8,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(137,'B001',NULL,NULL,NULL,NULL,14.5,9.3,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(138,'B002',NULL,NULL,NULL,NULL,14.5,10.5,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(139,'B003',NULL,NULL,NULL,NULL,14.5,11.8,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(140,'B004',NULL,NULL,NULL,NULL,14.5,13.2,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(141,'B005',NULL,NULL,NULL,NULL,14.5,14.5,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(142,'B006',NULL,NULL,NULL,NULL,14.5,15.8,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(143,'B007',NULL,NULL,NULL,NULL,14.5,17.2,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(144,'B008',NULL,NULL,NULL,NULL,14.5,18.5,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(145,'B009',NULL,NULL,NULL,NULL,14.5,19.7,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(146,'B010',NULL,NULL,NULL,NULL,14.5,21.2,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(147,'B011',NULL,NULL,NULL,NULL,14.5,22.5,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL),(148,'B012',NULL,NULL,NULL,NULL,14.5,23.8,180,'EMPTY',NULL,NULL,'2026-08-07 10:10:10.305734','2026-08-07 10:10:10.305734',2,13.25,NULL);
/*!40000 ALTER TABLE `storage_slot` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `traffic_control_event`
--

DROP TABLE IF EXISTS `traffic_control_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `traffic_control_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `vehicle_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '??/?? ?? ??',
  `event_type` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'HOLD(??) ?? RELEASE(??)',
  `reason_code` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '?? ?? ??. RELEASE ? ??? ?? ??',
  `reason_detail` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '??? ?? ?? ??',
  `counterpart_vehicle_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '???? ?? ?? ?? ?? ?? ??',
  `slot_code` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '?? ?? ??? ?? ?? ??',
  `distance_m` double DEFAULT NULL COMMENT '?? ?? ?? ??(m)',
  `command_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '? ???? ??? ??. ?? ??? NULL',
  `occurred_at` datetime(6) NOT NULL COMMENT '?? ??',
  PRIMARY KEY (`id`),
  KEY `idx_traffic_event_vehicle` (`vehicle_id`,`occurred_at`),
  KEY `idx_traffic_event_occurred` (`occurred_at`),
  CONSTRAINT `fk_traffic_event_vehicle` FOREIGN KEY (`vehicle_id`) REFERENCES `vehicle` (`vehicle_id`),
  CONSTRAINT `chk_traffic_event_reason` CHECK (((`reason_code` is null) or (`reason_code` in (_euckr'SAFETY_DISTANCE',_euckr'WORK_ZONE_OCCUPIED')))),
  CONSTRAINT `chk_traffic_event_type` CHECK ((`event_type` in (_euckr'HOLD',_euckr'RELEASE')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `traffic_control_event`
--

LOCK TABLES `traffic_control_event` WRITE;
/*!40000 ALTER TABLE `traffic_control_event` DISABLE KEYS */;
/*!40000 ALTER TABLE `traffic_control_event` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `transport_command`
--

DROP TABLE IF EXISTS `transport_command`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transport_command` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `command_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `task_id` bigint NOT NULL,
  `task_code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `vehicle_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `command_type` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL,
  `stage` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `failure_reason` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `published_at` datetime DEFAULT NULL,
  `acknowledged_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transport_command_command_id` (`command_id`),
  KEY `idx_transport_command_task` (`task_id`),
  KEY `idx_transport_command_vehicle` (`vehicle_id`),
  KEY `idx_transport_command_status` (`status`),
  KEY `idx_transport_command_created` (`created_at` DESC),
  CONSTRAINT `fk_transport_command_task` FOREIGN KEY (`task_id`) REFERENCES `transport_task` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `transport_command`
--

LOCK TABLES `transport_command` WRITE;
/*!40000 ALTER TABLE `transport_command` DISABLE KEYS */;
/*!40000 ALTER TABLE `transport_command` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `transport_task`
--

DROP TABLE IF EXISTS `transport_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `transport_task` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `task_code` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cargo_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `pallet_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '(구 컬럼) 현재 코드 미사용',
  `vehicle_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_x` double DEFAULT NULL,
  `source_y` double DEFAULT NULL,
  `source_heading` double DEFAULT NULL,
  `destination_slot_id` bigint DEFAULT NULL,
  `destination_x` double DEFAULT NULL,
  `destination_y` double DEFAULT NULL,
  `destination_heading` double DEFAULT NULL,
  `fork_height` double DEFAULT NULL,
  `cargo_orientation` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `assigned_at` datetime DEFAULT NULL,
  `started_at` datetime DEFAULT NULL,
  `picked_up_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `failed_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  `measurement_session_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'AI 세션 생성 후 연결된 세션 식별자',
  `measurement_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '측정 완료 후 연결되는 배치 판단 결과',
  `destination_slot_code` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '측정 완료 후 선택되는 목적지 적재 위치',
  `measurement_requested_at` datetime(6) DEFAULT NULL COMMENT 'AI 측정 요청 발행 시각. TTL 실패 판정 기준',
  `failure_code` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '실패 원인 코드. 관제 화면이 원인별 문구를 고르는 근거',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transport_task_task_code` (`task_code`),
  KEY `fk_transport_task_cargo` (`cargo_id`),
  KEY `idx_transport_task_status` (`status`),
  KEY `idx_transport_task_vehicle` (`vehicle_id`),
  KEY `idx_transport_task_pallet` (`pallet_id`),
  KEY `idx_transport_task_slot` (`destination_slot_id`),
  KEY `idx_transport_task_created` (`created_at` DESC),
  KEY `idx_transport_task_measurement_timeout` (`status`,`measurement_requested_at`),
  CONSTRAINT `fk_transport_task_cargo` FOREIGN KEY (`cargo_id`) REFERENCES `cargo` (`cargo_id`),
  CONSTRAINT `fk_transport_task_pallet` FOREIGN KEY (`pallet_id`) REFERENCES `pallet` (`pallet_id`),
  CONSTRAINT `fk_transport_task_slot` FOREIGN KEY (`destination_slot_id`) REFERENCES `storage_slot` (`id`),
  CONSTRAINT `chk_transport_task_failure_code` CHECK (((`failure_code` is null) or (`failure_code` in (_euckr'MEASUREMENT_NO_DETECTION',_euckr'MEASUREMENT_DISTANCE_UNRELIABLE',_euckr'MEASUREMENT_PALLET_NOT_DETECTED',_euckr'PLACEMENT_INELIGIBLE',_euckr'MEASUREMENT_NO_RESPONSE',_euckr'PLACEMENT_SLOT_UNAVAILABLE'))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `transport_task`
--

LOCK TABLES `transport_task` WRITE;
/*!40000 ALTER TABLE `transport_task` DISABLE KEYS */;
/*!40000 ALTER TABLE `transport_task` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `vehicle`
--

DROP TABLE IF EXISTS `vehicle`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `vehicle` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `vehicle_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `source` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '(구 컬럼) 현재 코드 미사용',
  `vehicle_type` varchar(30) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_vehicle_vehicle_id` (`vehicle_id`)
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `vehicle`
--

LOCK TABLES `vehicle` WRITE;
/*!40000 ALTER TABLE `vehicle` DISABLE KEYS */;
INSERT INTO `vehicle` VALUES (1,'REAL-F01','실물 지게차 1호','REAL',NULL,1,'2026-07-31 10:19:19','2026-08-03 12:18:49'),(2,'SIM-F01','시뮬레이션 지게차 1호','SIMULATION',NULL,1,'2026-07-31 10:19:19','2026-08-03 12:18:49'),(3,'SIM-F02','시뮬레이션 지게차 2호','SIMULATION',NULL,1,'2026-07-31 10:19:19','2026-07-31 10:19:19'),(9,'FORKLIFT-01','Forklift 01',NULL,NULL,1,'2026-08-03 12:18:39','2026-08-03 12:18:49'),(10,'FORKLIFT-02','Forklift 02',NULL,NULL,1,'2026-08-03 12:18:39','2026-08-03 12:18:49'),(19,'sim03','sim03',NULL,NULL,1,'2026-08-06 09:03:24','2026-08-06 09:03:24'),(20,'fk01','fk01',NULL,NULL,1,'2026-08-06 15:44:43','2026-08-06 15:44:43');
/*!40000 ALTER TABLE `vehicle` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `vehicle_command`
--

DROP TABLE IF EXISTS `vehicle_command`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `vehicle_command` (
  `command_id` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '차량 명령 고유 식별자',
  `task_id` bigint DEFAULT NULL COMMENT '연결된 운반 작업 식별자',
  `vehicle_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '명령 대상 차량 식별자',
  `command` varchar(30) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '실행할 명령 이름',
  `target_system` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '명령 대상 시스템(ROS2, EMBEDDED, ALL)',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '명령 처리 상태',
  `result_message` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '명령 처리 결과 또는 실패 상세',
  `completed_at` datetime(6) DEFAULT NULL COMMENT '명령 완료 시각',
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '명령 생성 시각',
  PRIMARY KEY (`command_id`),
  KEY `idx_vehicle_command_task` (`task_id`),
  KEY `idx_vehicle_command_vehicle` (`vehicle_id`),
  KEY `idx_vehicle_command_status` (`status`),
  CONSTRAINT `fk_vehicle_command_task` FOREIGN KEY (`task_id`) REFERENCES `transport_task` (`id`),
  CONSTRAINT `fk_vehicle_command_vehicle` FOREIGN KEY (`vehicle_id`) REFERENCES `vehicle` (`vehicle_id`),
  CONSTRAINT `chk_vehicle_command_status` CHECK ((`status` in (_utf8mb4'PENDING',_utf8mb4'PUBLISHED',_utf8mb4'PUBLISH_FAILED',_utf8mb4'ACCEPTED',_utf8mb4'IN_PROGRESS',_utf8mb4'SUCCESS',_utf8mb4'FAILED',_utf8mb4'REJECTED',_utf8mb4'CANCELLED'))),
  CONSTRAINT `chk_vehicle_command_target` CHECK ((`target_system` in (_utf8mb4'ROS2',_utf8mb4'EMBEDDED',_utf8mb4'ALL')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `vehicle_command`
--

LOCK TABLES `vehicle_command` WRITE;
/*!40000 ALTER TABLE `vehicle_command` DISABLE KEYS */;
INSERT INTO `vehicle_command` VALUES ('088fcd6c-f563-4ef8-ab9c-29bfa27d7ac0',NULL,'SIM-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:40:57.061727'),('0abd236d-d66a-4c92-8c25-f1fd197c96e7',NULL,'SIM-F02','STOP','EMBEDDED','PUBLISHED',NULL,NULL,'2026-08-06 12:40:42.411942'),('0f98ec71-f25b-4ff0-b36c-a8e611ee21e8',NULL,'SIM-F02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:51.352143'),('0fa556d6-1531-4feb-b61b-03a93e4b07b9',NULL,'FORKLIFT-02','EMERGENCY_STOP','ALL','PUBLISH_FAILED','Failed to publish MQTT message: topic=forklift/FORKLIFT-02/command',NULL,'2026-08-04 09:02:12.144318'),('13f8c8c2-ebef-4e05-aaa6-0c1d62d25ee6',NULL,'FORKLIFT-02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:40:39.131626'),('185f9a3c-cf71-45be-9c02-ee224a744bee',NULL,'sim03','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:41:19.548713'),('1a7882b2-8d26-493e-aa35-2f7cf8926839',NULL,'REAL-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:40:39.137151'),('206b48d8-8f33-4e54-a563-7d040d2331fb',NULL,'SIM-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:50.552541'),('38782bae-678d-4c59-a217-da9410ead9e4',NULL,'FORKLIFT-01','EMERGENCY_STOP','ALL','PUBLISH_FAILED','Failed to publish MQTT message: topic=forklift/FORKLIFT-01/command',NULL,'2026-08-04 09:02:08.021177'),('3f1d0573-2743-42ac-88de-d9fedecd0d57',NULL,'SIM-F02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 11:52:01.044271'),('41c89ba6-252b-47c7-8921-97a5673dd145',NULL,'SIM-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:51.345891'),('42794fc1-1a77-48bc-8785-cd2ff2e2c249',NULL,'SIM-F02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 11:51:52.867302'),('430db631-1f81-48f7-b1c6-0b1a22feb40c',NULL,'sim03','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:40:57.072989'),('44ba98d0-1185-478a-8eec-376516160750',NULL,'REAL-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:50.544024'),('4747c4cd-2a37-4ee0-80a6-7efcaea9e358',NULL,'REAL-F01','EMERGENCY_STOP','ALL','PUBLISH_FAILED','Failed to publish MQTT message: topic=forklift/REAL-F01/command',NULL,'2026-08-04 09:01:47.796790'),('4b71394a-b023-4fe8-ba5a-ec5034c69941',NULL,'SIM-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:49.717397'),('4be5f030-9b1a-497c-971f-6f39eacf4fdb',NULL,'SIM-F02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:40:41.212984'),('5ae8b65c-df3e-4b66-bb3b-d2347b3bfa53',NULL,'sim03','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:51.357261'),('62872e99-0df0-474b-ac70-78b98eee3f45',NULL,'SIM-F01','EMERGENCY_STOP','ALL','PUBLISH_FAILED','Failed to publish MQTT message: topic=forklift/SIM-F01/command',NULL,'2026-08-04 09:02:20.423751'),('63b3c02a-a2ff-4439-a4ef-84d7f5ccbbeb',NULL,'SIM-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:40:39.141152'),('6658729b-6628-407d-af39-a5d43f4a6848',NULL,'SIM-F02','EMERGENCY_STOP','ALL','PUBLISH_FAILED','Failed to publish MQTT message: topic=forklift/SIM-F02/command',NULL,'2026-08-04 09:02:24.564926'),('6a70bef7-cafa-42fe-92cc-b68b7fff0b2b',NULL,'FORKLIFT-01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:50.531002'),('71689114-4dd0-4043-8565-25b697349bd8',NULL,'FORKLIFT-02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:51.332371'),('730b7bf0-ee88-4a8d-baf1-ff3c9ea5a32d',NULL,'REAL-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:51.339383'),('78003e0b-f83b-4493-a7ce-6d26cf7a163d',NULL,'FORKLIFT-01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:40:39.123625'),('7e8417fc-e3af-4c32-859b-217127b2536b',NULL,'sim03','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 11:52:01.050283'),('82fd7b96-40b4-460b-9a5d-ec2b952c4429',NULL,'SIM-F02','STOP','EMBEDDED','PUBLISHED',NULL,NULL,'2026-08-06 12:01:40.304633'),('8479eb36-5495-47fd-bda0-9f4e814f2d2c',NULL,'sim03','STOP','EMBEDDED','PUBLISHED',NULL,NULL,'2026-08-06 13:41:49.987755'),('9321e1e3-ab33-4d14-8960-b811471b3665',NULL,'REAL-F01','EMERGENCY_STOP','ALL','PUBLISH_FAILED','Failed to publish MQTT message: topic=forklift/REAL-F01/command',NULL,'2026-08-04 09:02:16.270694'),('964674dc-7252-47df-bbed-3830067fae1e',NULL,'SIM-F02','STOP','EMBEDDED','PUBLISHED',NULL,NULL,'2026-08-06 11:51:55.726625'),('9aab2aae-86e7-4fb4-bdf6-0a2bf80e08b0',NULL,'REAL-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:40:57.057711'),('9f905533-8a38-4cbd-95a3-43c0cf76b661',NULL,'SIM-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:41:19.539200'),('a141dd51-bda5-4e3f-9756-c1066c8a1668',NULL,'sim03','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:44.835336'),('a8a05936-deed-48a6-a3fc-2ab774a2a8c0',NULL,'FORKLIFT-01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:41:19.505929'),('a8f1c129-2c0e-4aed-b1ef-e235da8e6cc3',NULL,'SIM-F02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:41:19.544205'),('ad65ded8-0ee7-4993-b228-a52496e154ef',NULL,'sim03','STOP','EMBEDDED','PUBLISHED',NULL,NULL,'2026-08-06 12:02:02.343235'),('aff43f17-66e6-4e0f-82a9-5c0b70e27552',NULL,'SIM-F02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:40:39.147209'),('b05f9580-aabd-4bc4-8c9f-a5431fcbd0e0',NULL,'FORKLIFT-02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:50.538514'),('b0de770d-177d-4dfa-a1fb-341fbbe6c283',NULL,'REAL-F01','EMERGENCY_STOP','ALL','PUBLISH_FAILED','Failed to publish MQTT message: topic=forklift/REAL-F01/command',NULL,'2026-08-04 09:01:56.849426'),('b9cb0050-5e91-4f3e-8a30-6f24fe339f04',NULL,'SIM-F02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:49.723264'),('bc71e062-10c4-45ed-8541-e47fc2d6d52f',NULL,'FORKLIFT-02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:49.705781'),('bc8c64f8-6e29-43cb-883b-e110defd4858',NULL,'REAL-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 11:52:01.033411'),('bf361352-7f74-44f9-8c1f-88373379dce6',NULL,'sim03','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:40:39.152772'),('bf6fcabe-e396-4e72-a4a7-b5830749bc13',NULL,'FORKLIFT-01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:49.699261'),('c370bc52-65fc-45ef-b8f9-27f520bafc11',NULL,'FORKLIFT-02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:40:57.051196'),('ca8cd96d-ef39-4f02-99ba-aac4236713e9',NULL,'SIM-F02','STOP','EMBEDDED','PUBLISHED',NULL,NULL,'2026-08-06 12:01:59.459289'),('cae38899-6d7e-49cf-a5d5-e2db7098c8ae',NULL,'FORKLIFT-02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 11:52:01.028407'),('d0a3e09f-ef39-4248-b9cc-6a1e5cbc7217',NULL,'REAL-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:41:19.533685'),('d0d6edb6-9a88-41cf-bf0d-0284608ff5d3',NULL,'REAL-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:49.710782'),('d567d778-460e-47da-b1ee-4d0d146aacbe',NULL,'FORKLIFT-01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 11:52:01.022522'),('d6519e13-ae7c-4499-ae08-d443c3ef195e',NULL,'SIM-F01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 11:52:01.038992'),('d927eab1-3fa4-4c0d-9893-777e8aea2feb',NULL,'SIM-F02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:40:57.066723'),('db40efb7-bc96-4efe-9250-45fb7775722d',NULL,'SIM-F02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:50.557948'),('e0143036-f32e-4eb9-a801-3dd1c528ae44',NULL,'FORKLIFT-02','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:41:19.525063'),('e0626031-b4e0-4cae-a6b7-251b7770a52a',NULL,'SIM-F02','STOP','EMBEDDED','PUBLISHED',NULL,NULL,'2026-08-06 11:51:58.004448'),('e5305e50-9600-49fc-b877-d582ff10f304',NULL,'FORKLIFT-01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:51.323859'),('e5eafcd7-718d-482d-9ec2-5c678556b879',NULL,'sim03','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:02:05.880273'),('ea0552e7-e426-4cd3-b611-48f15aa3e57c',NULL,'sim03','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:49.728264'),('eaafa733-b815-4489-b74c-dad8b6d94d91',NULL,'FORKLIFT-01','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:40:57.043687'),('ef2526b0-f60f-4cd2-aa08-0073c3e55517',NULL,'REAL-F01','STOP','EMBEDDED','PUBLISH_FAILED','Failed to publish MQTT message: topic=forklift/REAL-F01/command',NULL,'2026-08-04 09:02:02.808823'),('f012c94e-eccc-42a8-9442-3efaa2aa015b',NULL,'sim03','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 13:41:50.649096'),('f924b008-2144-4120-b10c-f2a4fa3f5d08',NULL,'sim03','EMERGENCY_STOP','ALL','PUBLISHED',NULL,NULL,'2026-08-06 12:01:50.563831');
/*!40000 ALTER TABLE `vehicle_command` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `vehicle_current_status`
--

DROP TABLE IF EXISTS `vehicle_current_status`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `vehicle_current_status` (
  `vehicle_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'UNKNOWN',
  `battery` int DEFAULT NULL,
  `position_x` double DEFAULT NULL,
  `position_y` double DEFAULT NULL,
  `heading` double DEFAULT NULL,
  `speed` double DEFAULT NULL,
  `fork_height` double DEFAULT NULL,
  `has_cargo` tinyint(1) DEFAULT NULL,
  `cargo_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `footprint_length` double DEFAULT NULL,
  `footprint_width` double DEFAULT NULL,
  `message_at` datetime DEFAULT NULL,
  `received_at` datetime NOT NULL,
  `updated_at` datetime(6) DEFAULT CURRENT_TIMESTAMP(6),
  `position_frame` varchar(10) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '좌표계(map 또는 odom)',
  PRIMARY KEY (`vehicle_id`),
  CONSTRAINT `fk_vehicle_current_status_vehicle` FOREIGN KEY (`vehicle_id`) REFERENCES `vehicle` (`vehicle_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `vehicle_current_status`
--

LOCK TABLES `vehicle_current_status` WRITE;
/*!40000 ALTER TABLE `vehicle_current_status` DISABLE KEYS */;
INSERT INTO `vehicle_current_status` VALUES ('fk01','UNKNOWN',NULL,4.93,9.35,270.7675529863256,1.5,NULL,NULL,NULL,NULL,NULL,'2026-08-07 01:13:12','2026-08-06 16:13:12','2026-08-06 15:44:43.435513','map'),('FORKLIFT-01','IDLE',100,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-08-03 12:15:14','2026-08-03 12:15:13.733670',NULL),('FORKLIFT-02','UNKNOWN',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-08-03 12:15:14','2026-08-03 12:15:13.733670',NULL),('REAL-F01','IDLE',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-31 10:26:26','2026-07-31 10:26:26','2026-07-31 10:26:26.000000',NULL),('SIM-F01','IDLE',100,1.2,3.4,90,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-31 10:50:25','2026-07-31 10:50:25','2026-07-31 10:50:25.000000',NULL),('SIM-F02','ERROR',15,57.423,-9.711,341.596595620398,0,NULL,NULL,NULL,NULL,NULL,'2026-08-07 00:51:33','2026-08-06 15:51:33','2026-07-31 10:26:26.000000','map'),('sim03','UNKNOWN',NULL,51.91,-9.706,341.596595620398,0,NULL,NULL,NULL,NULL,NULL,'2026-08-07 00:51:33','2026-08-06 15:51:33','2026-08-06 09:03:24.178233','map');
/*!40000 ALTER TABLE `vehicle_current_status` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `vehicle_fork_current_status`
--

DROP TABLE IF EXISTS `vehicle_fork_current_status`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `vehicle_fork_current_status` (
  `forklift_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `fork_state` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `limit_bottom` tinyint(1) NOT NULL,
  `error_code` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `message_at` datetime NOT NULL,
  `received_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`forklift_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `vehicle_fork_current_status`
--

LOCK TABLES `vehicle_fork_current_status` WRITE;
/*!40000 ALTER TABLE `vehicle_fork_current_status` DISABLE KEYS */;
/*!40000 ALTER TABLE `vehicle_fork_current_status` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `vehicle_load_safety`
--

DROP TABLE IF EXISTS `vehicle_load_safety`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `vehicle_load_safety` (
  `vehicle_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `cargo_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `fork_height` double DEFAULT NULL,
  `cargo_height` double DEFAULT NULL,
  `roll_deg` double DEFAULT NULL,
  `pitch_deg` double DEFAULT NULL,
  `load_offset_x` double DEFAULT NULL,
  `load_offset_y` double DEFAULT NULL,
  `risk_level` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `risk_code` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `message` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `detected_at` datetime NOT NULL,
  `received_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`vehicle_id`),
  KEY `idx_vehicle_load_safety_risk` (`risk_level`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `vehicle_load_safety`
--

LOCK TABLES `vehicle_load_safety` WRITE;
/*!40000 ALTER TABLE `vehicle_load_safety` DISABLE KEYS */;
/*!40000 ALTER TABLE `vehicle_load_safety` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `vehicle_status_history`
--

DROP TABLE IF EXISTS `vehicle_status_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `vehicle_status_history` (
  `history_id` bigint NOT NULL AUTO_INCREMENT COMMENT '차량 상태 이력 식별자',
  `vehicle_id` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL,
  `battery` int DEFAULT NULL,
  `position_x` double DEFAULT NULL,
  `position_y` double DEFAULT NULL,
  `heading` double DEFAULT NULL,
  `speed` double DEFAULT NULL,
  `fork_height` double DEFAULT NULL,
  `has_cargo` tinyint(1) DEFAULT NULL,
  `cargo_id` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `footprint_length` double DEFAULT NULL,
  `footprint_width` double DEFAULT NULL,
  `message_at` datetime DEFAULT NULL,
  `received_at` datetime NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`history_id`),
  KEY `idx_vehicle_status_history_vehicle_message` (`vehicle_id`,`message_at` DESC),
  KEY `idx_vehicle_status_history_message` (`message_at`),
  CONSTRAINT `fk_vehicle_status_history_vehicle` FOREIGN KEY (`vehicle_id`) REFERENCES `vehicle` (`vehicle_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `vehicle_status_history`
--

LOCK TABLES `vehicle_status_history` WRITE;
/*!40000 ALTER TABLE `vehicle_status_history` DISABLE KEYS */;
INSERT INTO `vehicle_status_history` VALUES (1,'SIM-F01','MOVING',77,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-31 10:48:54','2026-07-31 10:48:57','2026-07-31 10:48:57.000000'),(2,'SIM-F01','IDLE',100,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,'2026-07-31 10:50:25','2026-07-31 10:50:25','2026-07-31 10:50:25.000000');
/*!40000 ALTER TABLE `vehicle_status_history` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-08-07 12:08:16
