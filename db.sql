-- --------------------------------------------------------
-- Host:                         127.0.0.1
-- Server version:               12.1.2-MariaDB - MariaDB Server
-- Server OS:                    Win64
-- HeidiSQL Version:             12.11.0.7065
-- --------------------------------------------------------

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET NAMES utf8 */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

CREATE DATABASE IF NOT EXISTS `finalJava` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_uca1400_ai_ci */;
USE `finalJava`;

CREATE TABLE IF NOT EXISTS `messages` (
  `messageID` int(11) NOT NULL AUTO_INCREMENT,
  `message` varchar(255) NOT NULL,
  `sentOn` timestamp NOT NULL,
  `userID` int(11) NOT NULL,
  `imageURL` varchar(50),
  `channelID` int(11),
  `dmID` int(11)
  PRIMARY KEY (`messageID`),
  UNIQUE KEY `message_sentOn_userID` (`message`,`sentOn`,`userID`),
  KEY `messages_users_fk` (`userID`),
  CONSTRAINT `messages_users_fk` FOREIGN KEY (`userID`) REFERENCES `users` (`userID`) ON DELETE NO ACTION ON UPDATE NO ACTION
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

CREATE TABLE IF NOT EXISTS `users` (
  `userID` int(11) NOT NULL AUTO_INCREMENT,
  `userName` varchar(50) NOT NULL,
  `PW_Hash` binary(60) NOT NULL,
  `token` varchar(64) NOT NULL,
  PRIMARY KEY (`userID`),
  UNIQUE KEY `userName` (`userName`),
  UNIQUE KEY `token` (`token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

CREATE TABLE IF NOT EXISTS `servers` (
  `serverID` int(11) NOT NULL AUTO_INCREMENT,
  `name` varchar(50) NOT NULL,
  `ownerID` int(11) NOT NULL,
  `isPublic` boolean NOT NULL,
  PRIMARY KEY (`serverID`),
  CONSTRAINT `servers_users_fk` FOREIGN KEY (`ownerID`) REFERENCES `users` (`userID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `server_members` (
  `serverID` int(11) NOT NULL,
  `userID` int(11) NOT NULL,
  `joinedAt` timestamp NOT NULL,
  PRIMARY KEY(`serverID`, `userID`),
  CONSTRAINT `servers_members_server_fk` FOREIGN KEY (`serverID`) REFERENCES `servers` (`serverID`),
  CONSTRAINT `servers_members_users_fk` FOREIGN KEY (`userID`) REFERENCES `users` (`userID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `server_invites` (
  `serverID` int(11) NOT NULL,
  `inviteCode` int(11) NOT NULL AUTO_INCREMENT,
  `createdBy` varchar(50),
  PRIMARY KEY (`inviteCode`), 
  CONSTRAINT `server_invites__server_fk` FOREIGN KEY (`serverID`) REFERENCES `servers` (`serverID`),
  CONSTRAINT `servers_invites_users_fk` FOREIGN KEY (`createdBy`) REFERENCES `users` (`userName`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `channels`(
  `channelID` int(11) NOT NULL AUTO_INCREMENT,
  `serverID` int(11) NOT NULL,
  `name` varchar(50) NOT NULL,
  PRIMARY KEY (`channelID`),
  CONSTRAINT `channels__server_fk` FOREIGN KEY (`serverID`) REFERENCES `servers` (`serverID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `direct_messages`(
  `dmID` int(11) NOT NULL AUTO_INCREMENT,
  `user1ID` int(11) NOT NULL,
  `user2ID` int(11) NOT NULL,
  PRIMARY KEY (`dmID`),
  UNIQUE KEY (`userID1`,`userID2`),
  CONSTRAINT `direct_messages_user1_fk` FOREIGN KEY (`user1ID`) REFERENCES `users` (`userID`),
  CONSTRAINT `direct_messages_user2_fk` FOREIGN KEY (`user2ID`) REFERENCES `users` (`userID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `roles`(
  `roleID` int(11) NOT NULL AUTO_INCREMENT,
  `serverID` int(11) NOT NULL,
  `name` varchar(50) NOT NULL,
  PRIMARY KEY(`roleID`),
  UNIQUE KEY(`serverID`, `name`),
  CONSTRAINT `roles_server_fk` FOREIGN KEY (`serverID`) REFERENCES `servers` (`serverID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `user_roles`(
  `userRoleId` int(11) NOT NULL AUTO_INCREMENT,
  `userID` int(11) NOT NULL,
  `roleID` int(11) NOT NULL,
  PRIMARY KEY(`userRoleId`),
  CONSTRAINT `user_roles_user_fk` FOREIGN KEY (`userID`) REFERENCES `users` (`userID`),
  CONSTRAINT `user_roles_role_fk` FOREIGN KEY (`roleID`) REFERENCES `roles` (`roleID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `channel_role_perms`(
  `channelID` int(11) NOT NULL,
  `roleID` int(11) NOT NULL,
  `canRead` boolean NOT NULL,
  `canWrite` boolean NOT NULL,
  PRIMARY KEY(`channelID`, `roleID`),
  CONSTRAINT `channel_role_channelID_fk` FOREIGN KEY (`channelID`) REFERENCES `channels` (`channelID`),
  CONSTRAINT `channel_role_roleID_fk` FOREIGN KEY (`roleID`) REFERENCES `roles` (`roleID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `member_permissions`(
  `userID` int(11) NOT NULL,
  `serverID` int(11) NOT NULL,
  `canInvite` boolean NOT NULL,
  `canKick` boolean NOT NULL,
  `canCreateChannels` boolean NOT NULL,
  PRIMARY KEY(`userID`, `serverID`),
  CONSTRAINT `member_permissions_userID_fk` FOREIGN KEY (`userID`) REFERENCES `users` (`userID`),
  CONSTRAINT `member_permissions_serverID_fk` FOREIGN KEY (`serverID`) REFERENCES `servers` (`serverID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


CREATE TABLE IF NOT EXISTS `friends`(
  `userID` int(11) NOT NULL,
  `friendID` int(11) NOT NULL,
  `status` varchar(50) NOT NULL,
  PRIMARY KEY(`userID`,`friendID`),
  CONSTRAINT `friends_userID_fk` FOREIGN KEY (`userID`) REFERENCES `users` (`userID`),
  CONSTRAINT `friends_friendID_fk` FOREIGN KEY (`friendID`) REFERENCES `users` (`userID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

/*!40103 SET TIME_ZONE=IFNULL(@OLD_TIME_ZONE, 'system') */;
/*!40101 SET SQL_MODE=IFNULL(@OLD_SQL_MODE, '') */;
/*!40014 SET FOREIGN_KEY_CHECKS=IFNULL(@OLD_FOREIGN_KEY_CHECKS, 1) */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40111 SET SQL_NOTES=IFNULL(@OLD_SQL_NOTES, 1) */;
