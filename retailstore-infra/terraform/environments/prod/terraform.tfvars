aws_region     = "us-east-1"
aws_account_id = "123456789012"  # Replace with your AWS account ID

# DB passwords — rotate via AWS Secrets Manager rotation in prod.
# Pass at apply time only — never commit real passwords to git:
#   terraform apply -var="catalog_db_password=<password>" -var="orders_db_password=<password>"
catalog_db_password = "CHANGE_ME"
orders_db_password  = "CHANGE_ME"
