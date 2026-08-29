-- The application owns eight MySQL schemas (databases). The application user
-- therefore needs privileges beyond the single schema created by the image.
GRANT ALL PRIVILEGES ON *.* TO 'ridehailing'@'%';
FLUSH PRIVILEGES;
