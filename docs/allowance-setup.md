# Budget and allowance setup

This feature tracks manually declared income and expenses and calculates a daily
allowance until the user's next declared payday. It uses the existing authenticated
Supabase client. No AI API, provider token, or Edge Function is required.

## Enable the feature

1. On a development Supabase project with `supabase/email_auth.sql` applied, run
   the complete `supabase/allowances.sql` file in SQL Editor as database owner.
   It adds two account-owned tables and read-only snapshot RPCs. Reapplying it
   preserves entries, adds planned/posted status and salary source columns, and
   treats all existing entries as posted. Apply this update before installing the
   updated APK. The original snapshot RPC remains compatible with older apps and
   excludes planned transactions. No tables or entries are dropped.
2. Build and launch Android yourself. Open **Profile & settings**, edit the
   dashboard feature selection, enable **Budget & allowance**, and save.
   The stored preference key is `Allowance`. This is a normal user preference;
   users do not need an administrator to enable it.
3. Open the dashboard card and choose **Budget settings**. Select a currency,
   a future next-payday date, and an optional amount to reserve. Then record actual
   income with **Add income** and spending with **Add expense**.

Supported two-decimal currencies: PHP, USD, EUR, GBP, SGD, AUD, CAD, CNY and INR.
One ledger uses one currency. Currency is locked while transactions exist; amounts
are never converted. An opening balance can be entered as an income transaction
with the description “Opening balance”. Categories and notes are user-entered.

## Calculation rules

- Remaining balance = all recorded income minus all recorded expenses.
- Spendable balance = remaining balance minus the manually reserved amount.
- Daily allowance = positive spendable balance divided by calendar days before
  the next payday, rounded down to two decimal places.
- Today is included; the payday itself is excluded. If payday is today or earlier,
  the app asks for an updated payday instead of dividing by zero or crediting income.
- Deficits stay visible, and the daily allowance is zero when funds are exhausted.
  Money and calculations use `BigDecimal` in Java and `numeric` in PostgreSQL.
- Income, expenses and the next payday are all manual. There are no bank imports,
  recurring salary deposits, automatic bill payments, or automatic payday rollovers.
- When paying a reserved bill, record the expense and reduce the reservation to
  avoid setting aside the same amount twice.

For example, 10,000 income minus 3,000 expenses and 2,000 reserved leaves 5,000
spendable. With 10 days before payday, the daily allowance is 500.

Dates follow the device's local calendar day. Only posted transactions dated today
or earlier count toward actual balances. The next payday is a date,
so weekly, monthly, student allowances and irregular pay schedules use the same
calculation. Date and timezone changes recalculate while the screen is active;
returning to the screen or tapping Refresh reloads the database. Changes made on
another device appear on refresh, rather than through a live subscription.

## Salary declarations and advance planning

- Choose **Declare expected salary**, enter an amount and salary date, then save.
  The date defaults to the budget payday; saving does not change that setting.
  Each declaration represents one salary payment, with no automatic recurrence.
- In **Add income** or **Add expense**, select **Plan this transaction** to declare
  future or pending amounts. Choosing a future date selects it automatically.
  Posted transactions cannot use future dates. Planned entries remain pending
  after their due date until explicitly confirmed, edited, or deleted.
- Under **Plan ahead**, select today or a future forecast date. Projected balance
  is actual current balance plus planned income minus planned expenses due on or
  before that date, including overdue plans. Projected spendable subtracts the
  current reserve. It is an estimate; actual daily allowance stays unchanged.
- On the salary date, choose **Salary received** on its transaction. For other
  plans choose **Mark received** or **Mark paid**. Confirm the actual amount and
  date (default today). Saving updates the same row from planned to posted,
  preventing double counting; it does not insert a second payment. Partial
  payments are not split automatically: declare a separate plan for any remainder.

For example, a current balance of 5,000, planned salary of 10,000, and planned
expenses of 3,000 project 12,000 through their due dates. With a 2,000 reserve,
projected spendable is 10,000. Confirming the salary at 9,500 replaces its expected
10,000 with actual income of 9,500, so the revised projected balance is 11,500.

## Data access and history

`allowance_settings` stores currency, payday and reserved funds.
`allowance_entries` stores the manual transaction ledger. Both use the verified
profile mapping through `stride_auth.can_access_profile`, with row-level security
and no anonymous access. The app never needs a service-role key.

`allowance_forecast_snapshot` reads actual totals, forecast aggregates, and history
from one database snapshot. `allowance_snapshot` supports older apps with only
posted entries. Totals are returned as decimal strings to preserve cents.
History loads in pages of 50 using a date/ID cursor, so summaries never add only
the visible page. Refresh restarts history from the first page.

Updates compare server-owned revisions to avoid silently overwriting another
device's edits. Each new editor keeps its UUID through retries, and uncertain
write responses are checked against the saved record before reporting success.
If an edit conflicts, refresh and reopen it. Deleting an entry permanently changes
the ledger; the confirmation dialog explains that balances will be recalculated.

## File responsibilities and verification

Models hold immutable data. `AllowanceCalculator` performs actual arithmetic and
`AllowanceForecastCalculator` projects balances from full-ledger aggregates;
`AllowanceMoney` parses and formats amounts; `AllowanceRepository` handles data
access. `AllowanceViewModel` coordinates state and actions. The screen, entry
adapter and three editors render their respective views through View Binding.

Build and runtime testing remain with the repository owner. Verify first setup,
manual income and expense entry, editing, deletion, reservations, exhausted funds,
payday arrival, changing the local date, amounts with cents, more than 50 entries,
refresh after another device's edit, currency locking, sign-out, and isolation
between two accounts. Check existing dashboard preferences and the protected
`generatevids` entitlement when saving Settings.

Also verify future income and expenses, forecast date boundaries, overdue plans,
salary confirmation with a changed actual amount/date, cancellation, confirming
the same row from two devices, and retry after an uncertain response. Ensure
plans beyond the first 50 rows still affect forecasts and that existing posted
entries and older app versions retain their actual balances after the migration.
