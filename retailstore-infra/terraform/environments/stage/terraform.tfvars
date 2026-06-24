aws_region     = "us-east-1"
aws_account_id = "123456789012"  # Replace with your AWS account ID

# DB passwords — use AWS Secrets Manager in prod; for stage, set via CLI:
#   terraform apply -var="catalog_db_password=<password>" -var="orders_db_password=<password>"
# Never commit real passwords to git.
catalog_db_password = "CHANGE_ME"
orders_db_password  = "CHANGE_ME"
