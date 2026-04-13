#!/bin/bash
# =============================================================================
# Market Data Service – Cron Job Setup
# =============================================================================
#
# Vietnamese stock market trading hours: 09:00–15:30 ICT (UTC+7)
# The crawler should run AFTER the market closes, i.e. after 15:30 ICT.
# 15:30 ICT = 08:30 UTC, so we schedule the job at 08:30 UTC on weekdays.
#
# HOW TO INSTALL
# --------------
# 1. Open your crontab for editing:
#       crontab -e
#
# 2. Add the line below (adjust /path/to/project to your actual project root):
#
#       30 8 * * 1-5 cd /path/to/project && python -m market_data_service.scheduler.main >> /path/to/project/logs/cron.log 2>&1
#
# 3. Save and exit. Verify with:
#       crontab -l
#
# CRON FIELD REFERENCE
# --------------------
#   ┌─────────── minute  (0–59)
#   │  ┌──────── hour    (0–23, UTC)
#   │  │  ┌───── day of month (1–31)
#   │  │  │  ┌── month  (1–12)
#   │  │  │  │  ┌─ day of week (0–7, 0 and 7 = Sunday; 1–5 = Mon–Fri)
#   │  │  │  │  │
#   30 8  *  * 1-5  <command>
#
# BACKFILL (one-time historical load)
# ------------------------------------
# To fetch 1 year of historical data, run once manually with --backfill:
#
#   cd /path/to/project && python -m market_data_service.scheduler.main --backfill
#
# FORCE RUN (bypass trading hours check)
# ----------------------------------------
# To run outside trading hours (e.g. for testing):
#
#   cd /path/to/project && python -m market_data_service.scheduler.main --force
#
# ENVIRONMENT VARIABLES
# ----------------------
# Ensure the following are set in the environment or in a .env file:
#   SUPABASE_URL          – Your Supabase project URL
#   SUPABASE_SERVICE_KEY  – Your Supabase service role key
#
# Example .env file location: market_data_service/.env
# =============================================================================

# Example cron entry (copy this line into `crontab -e`):
# 30 8 * * 1-5 cd /path/to/project && python -m market_data_service.scheduler.main >> /path/to/project/logs/cron.log 2>&1
