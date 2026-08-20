package com.humblesolutions.aromex.i18n

object Strings {
    const val splash_app_name = "splash_app_name"
    const val splash_tagline = "splash_tagline"

    // Login screen
    const val login_title = "login_title"
    const val login_email_label = "login_email_label"
    const val login_password_label = "login_password_label"
    const val login_submit = "login_submit"
    const val login_loading = "login_loading"

    // Login — ticket #21 redesign
    const val login_welcome = "login_welcome"                    // "Welcome back"
    const val login_welcome_subtitle = "login_welcome_subtitle"  // "Sign in to your account to continue"
    const val login_email_label_upper = "login_email_label_upper" // "EMAIL ADDRESS"
    const val login_email_placeholder = "login_email_placeholder" // "you@company.com"
    const val login_password_label_upper = "login_password_label_upper" // "PASSWORD"
    const val login_password_placeholder = "login_password_placeholder" // "Enter your password"
    const val login_forgot_password = "login_forgot_password"    // "Forgot password?"
    const val login_need_access = "login_need_access"            // "Need access?"
    const val login_contact_admin = "login_contact_admin"        // "Contact your administrator"
    const val login_forgot_password_soon = "login_forgot_password_soon" // toast body

    // Accessibility labels for the password eye toggle.
    const val login_password_show = "login_password_show"        // "Show password"
    const val login_password_hide = "login_password_hide"        // "Hide password"

    // Login — ticket #23 desktop redesign (left brand panel + footer)
    const val login_desktop_eyebrow = "login_desktop_eyebrow"            // "POINT OF SALE"
    const val login_desktop_headline = "login_desktop_headline"          // "Built for phone retailers"
    const val login_desktop_body = "login_desktop_body"                  // marketing paragraph
    const val login_desktop_product_badge = "login_desktop_product_badge" // "A Humble Solutions Product"
    const val login_desktop_platform_tag = "login_desktop_platform_tag"  // "desktop" (appended to version)
    const val login_desktop_version_fallback = "login_desktop_version_fallback" // "v2.4.1"

    // Login errors
    const val login_error_unknown_email = "login_error_unknown_email"
    const val login_error_wrong_password = "login_error_wrong_password"
    const val login_error_account_disabled = "login_error_account_disabled"
    const val login_error_missing_user_record = "login_error_missing_user_record"
    const val login_error_network = "login_error_network"
    const val login_error_gateway = "login_error_gateway"
    const val login_error_firebase = "login_error_firebase"
    const val login_error_unexpected = "login_error_unexpected"

    // Choose-company screen
    const val choose_company_title = "choose_company_title"
    const val choose_company_subtitle = "choose_company_subtitle"

    // Home placeholder
    const val home_title = "home_title"
    const val home_welcome = "home_welcome"          // takes {0}=email
    const val home_role = "home_role"                // takes {0}=role
    const val home_signed_in_as = "home_signed_in_as" // takes {0}=email
    const val home_sign_out = "home_sign_out"

    // Home — balances panel
    const val home_balances_title = "home_balances_title"
    const val home_balances_loading = "home_balances_loading"
    const val home_balances_empty = "home_balances_empty"
    const val home_balances_error = "home_balances_error"
    const val home_balances_retry = "home_balances_retry"
    const val home_balances_section_cash = "home_balances_section_cash"
    const val home_balances_section_bank = "home_balances_section_bank"
    const val home_balances_section_credit_card = "home_balances_section_credit_card"
    const val home_balances_section_other = "home_balances_section_other"

    // HL error messages
    const val hl_error_network = "hl_error_network"
    const val hl_error_gateway = "hl_error_gateway"
    const val hl_error_token_rejected = "hl_error_token_rejected"
    const val hl_error_hl_unreachable = "hl_error_hl_unreachable"
    const val hl_error_unauthorized = "hl_error_unauthorized"
    const val hl_error_unexpected = "hl_error_unexpected"

    // Account type labels
    const val account_type_cash = "account_type_cash"
    const val account_type_bank = "account_type_bank"
    const val account_type_credit_card = "account_type_credit_card"
    const val account_type_receivable = "account_type_receivable"
    const val account_type_payable = "account_type_payable"
    const val account_type_revenue = "account_type_revenue"
    const val account_type_expense = "account_type_expense"
    const val account_type_tax = "account_type_tax"
    const val account_type_other = "account_type_other"

    // ── Entities — ticket #37 (real UI: List / Detail / Add-Edit) ──────────────

    // List screen
    const val entities_list_eyebrow = "entities_list_eyebrow"            // "POS"
    const val entities_list_title = "entities_list_title"               // "Contacts"
    const val entities_search_placeholder = "entities_search_placeholder" // "Search contacts…"
    const val entities_add_cd = "entities_add_cd"                        // "Add contact" (a11y)
    const val entities_summary_to_receive = "entities_summary_to_receive" // "TO RECEIVE"
    const val entities_summary_to_give = "entities_summary_to_give"     // "TO GIVE"
    const val entities_row_to_receive = "entities_row_to_receive"       // "to receive"
    const val entities_row_to_give = "entities_row_to_give"             // "to give"
    const val entities_row_settled = "entities_row_settled"             // "settled"

    // List filters
    const val entities_filter_all = "entities_filter_all"               // "All"
    const val entities_filter_customers = "entities_filter_customers"   // "Customers"
    const val entities_filter_suppliers = "entities_filter_suppliers"   // "Suppliers"
    const val entities_filter_middlemen = "entities_filter_middlemen"   // "Middlemen"

    // List states
    const val entities_empty_title = "entities_empty_title"             // "No contacts yet"
    const val entities_empty_cta = "entities_empty_cta"                 // "Add your first contact"
    const val entities_loading = "entities_loading"                     // "Loading contacts…"
    const val entities_error_title = "entities_error_title"             // "Couldn't load contacts"
    const val entities_error_retry = "entities_error_retry"             // "Retry"
    const val entities_no_access = "entities_no_access"                 // permission-denied line

    // Detail screen
    const val entity_detail_eyebrow = "entity_detail_eyebrow"           // "CONTACT"
    const val entity_detail_back_cd = "entity_detail_back_cd"           // "Back"
    const val entity_detail_menu_cd = "entity_detail_menu_cd"           // "More actions"
    const val entity_detail_balance_label = "entity_detail_balance_label" // "BALANCE"
    const val entity_detail_they_owe_you = "entity_detail_they_owe_you" // "They owe you"
    const val entity_detail_you_owe_them = "entity_detail_you_owe_them" // "You owe them"
    const val entity_detail_settled = "entity_detail_settled"           // "Settled"
    const val entity_detail_transactions = "entity_detail_transactions" // "TRANSACTIONS"
    const val entity_detail_transactions_empty = "entity_detail_transactions_empty" // "More transactions will appear here"
    const val entity_detail_edit = "entity_detail_edit"                 // "Edit"
    const val entity_detail_archive = "entity_detail_archive"           // "Archive"
    const val entity_detail_delete = "entity_detail_delete"             // "Delete"
    const val entity_detail_walkin_note = "entity_detail_walkin_note"   // walk-in disabled note
    const val entity_detail_archive_title = "entity_detail_archive_title" // "Archive this contact?"
    const val entity_detail_archive_body = "entity_detail_archive_body"   // confirm body
    const val entity_detail_archive_confirm = "entity_detail_archive_confirm" // "Archive"
    const val entity_detail_cancel = "entity_detail_cancel"             // "Cancel"

    // Add / Edit form
    const val entity_form_new_eyebrow = "entity_form_new_eyebrow"       // "NEW ENTITY"
    const val entity_form_new_title = "entity_form_new_title"           // "Add Contact"
    const val entity_form_edit_eyebrow = "entity_form_edit_eyebrow"     // "EDIT ENTITY"
    const val entity_form_edit_title = "entity_form_edit_title"         // "Edit Contact"
    const val entity_form_close_cd = "entity_form_close_cd"             // "Close"
    const val entity_form_role_customer_title = "entity_form_role_customer_title"     // "Customer"
    const val entity_form_role_customer_subtitle = "entity_form_role_customer_subtitle" // "Issues invoices"
    const val entity_form_role_supplier_title = "entity_form_role_supplier_title"     // "Supplier"
    const val entity_form_role_supplier_subtitle = "entity_form_role_supplier_subtitle" // "Sends you bills"
    const val entity_form_section_contact = "entity_form_section_contact" // "CONTACT INFO"
    const val entity_form_section_notes = "entity_form_section_notes"     // "NOTES"
    const val entity_form_section_opening = "entity_form_section_opening" // "OPENING BALANCE"
    const val entity_form_full_name_label = "entity_form_full_name_label"       // "FULL NAME"
    const val entity_form_full_name_placeholder = "entity_form_full_name_placeholder" // "Full name"
    const val entity_form_phone_label = "entity_form_phone_label"       // "PHONE NUMBER"
    const val entity_form_phone_placeholder = "entity_form_phone_placeholder" // "Phone number"
    const val entity_form_add_phone = "entity_form_add_phone"           // "Add another number"
    const val entity_form_remove_phone_cd = "entity_form_remove_phone_cd" // "Remove number"
    const val entity_form_email_label = "entity_form_email_label"       // "EMAIL ADDRESS"
    const val entity_form_email_placeholder = "entity_form_email_placeholder" // "Email address"
    const val entity_form_address_label = "entity_form_address_label"   // "ADDRESS"
    const val entity_form_address_placeholder = "entity_form_address_placeholder" // "Street, city, region"
    // Tax number (ticket #106) — optional; printed on this party's invoices when they buy.
    const val entity_form_tax_number_label = "entity_form_tax_number_label" // "TAX NUMBER (OPTIONAL)"
    const val entity_form_tax_number_placeholder = "entity_form_tax_number_placeholder" // "GST/HST number for their invoice"
    const val entity_form_notes_placeholder = "entity_form_notes_placeholder" // "Internal notes about this contact"
    const val entity_form_opening_question = "entity_form_opening_question" // "Does this contact start with an existing balance?"
    const val entity_form_opening_to_receive = "entity_form_opening_to_receive" // "To Receive"
    const val entity_form_opening_to_give = "entity_form_opening_to_give" // "To Give"
    const val entity_form_amount_label = "entity_form_amount_label"     // "AMOUNT"
    const val entity_form_amount_placeholder = "entity_form_amount_placeholder" // "0.00"
    const val entity_form_opening_readonly_note = "entity_form_opening_readonly_note" // edit read-only note
    const val entity_form_save = "entity_form_save"                     // "Save Entity"
    const val entity_form_saving = "entity_form_saving"                 // "Saving…"

    // Validation
    const val entity_form_error_name_required = "entity_form_error_name_required" // "Name is required"
    const val entity_form_error_email_invalid = "entity_form_error_email_invalid" // "Enter a valid email address"

    // Unsaved-changes guard
    const val entity_form_unsaved_title = "entity_form_unsaved_title"   // "Discard changes?"
    const val entity_form_unsaved_body = "entity_form_unsaved_body"     // body
    const val entity_form_unsaved_discard = "entity_form_unsaved_discard" // "Discard changes"
    const val entity_form_unsaved_keep = "entity_form_unsaved_keep"     // "Keep editing"

    // Keyboard toolbar actions (iOS number/decimal pads need explicit buttons)
    const val entity_form_kbd_next = "entity_form_kbd_next"             // "Next"
    const val entity_form_kbd_done = "entity_form_kbd_done"             // "Done"

    // Country-code picker (searchable dropdown)
    const val country_picker_title = "country_picker_title"            // "Select country"
    const val country_picker_search = "country_picker_search"          // "Search country or code"
    const val country_picker_empty = "country_picker_empty"            // "No countries found"

    // ── Entities — ticket #38 (desktop shell additions) ──────────────────────

    // Top bar / list panel
    const val entities_new_contact_btn = "entities_new_contact_btn"    // "+ New Contact"
    const val entities_total_count = "entities_total_count"            // "{0} total"
    const val entities_summary_receivable = "entities_summary_receivable" // "RECEIVABLE"
    const val entities_summary_payable = "entities_summary_payable"    // "PAYABLE"
    const val entities_no_selection = "entities_no_selection"          // "Select a contact to view details"
    const val entities_transactions_label = "entities_transactions_label" // "Transactions"
    const val entities_transactions_add = "entities_transactions_add"  // "+ Add Transaction"
    const val entities_sign_out = "entities_sign_out"                  // "Sign out"

    // Detail panel field labels (shorter than form labels)
    const val entity_detail_phone_label = "entity_detail_phone_label"   // "PHONE"
    const val entity_detail_email_label = "entity_detail_email_label"   // "EMAIL"
    const val entity_detail_address_label = "entity_detail_address_label" // "ADDRESS"
    const val entity_detail_notes_label = "entity_detail_notes_label"   // "NOTES"

    // Nav sidebar labels
    const val entities_sidebar_dashboard = "entities_sidebar_dashboard" // "Dashboard"
    const val entities_sidebar_sales = "entities_sidebar_sales"         // "Sales"
    const val entities_sidebar_inventory = "entities_sidebar_inventory" // "Inventory"
    const val entities_sidebar_reports = "entities_sidebar_reports"     // "Reports"
    const val entities_sidebar_settings = "entities_sidebar_settings"   // "Settings"

    // Form — desktop-specific save button label
    const val entity_form_save_contact = "entity_form_save_contact"    // "Save Contact"

    // Offline / no-connection dialog (shared across screens)
    const val offline_title = "offline_title"                          // "You're offline"
    const val offline_body = "offline_body"                            // "Please reconnect to the internet and try again."
    const val offline_retry = "offline_retry"                          // "Retry"
    const val offline_dismiss = "offline_dismiss"                      // "Dismiss"

    // Add-Inventory flow — Screen 1: entry
    const val inventory_add_title = "inventory_add_title"              // "Add inventory"
    const val inventory_review_action = "inventory_review_action"      // "Review"
    const val inventory_close_cd = "inventory_close_cd"               // "Close"
    const val inventory_back_cd = "inventory_back_cd"                 // "Back"

    // SKU section
    const val inventory_sku_section = "inventory_sku_section"          // "PHONE"
    const val inventory_brand_label = "inventory_brand_label"          // "BRAND"
    const val inventory_brand_placeholder = "inventory_brand_placeholder" // "Select brand"
    const val inventory_model_label = "inventory_model_label"          // "MODEL"
    const val inventory_model_placeholder = "inventory_model_placeholder" // "Select model"
    const val inventory_model_hint_brand = "inventory_model_hint_brand" // "Select a brand first"
    const val inventory_capacity_label = "inventory_capacity_label"    // "CAPACITY"
    const val inventory_capacity_placeholder = "inventory_capacity_placeholder" // "Select capacity"
    const val inventory_color_label = "inventory_color_label"          // "COLOR"
    const val inventory_color_placeholder = "inventory_color_placeholder" // "Select color"
    const val inventory_carrier_label = "inventory_carrier_label"      // "CARRIER"
    const val inventory_carrier_placeholder = "inventory_carrier_placeholder" // "Select carrier"
    const val inventory_selling_price_label = "inventory_selling_price_label" // "SELLING PRICE"
    const val inventory_selling_price_placeholder = "inventory_selling_price_placeholder" // "0.00"

    // Batch details section
    const val inventory_batch_section = "inventory_batch_section"      // "BATCH DETAILS"
    const val inventory_cost_label = "inventory_cost_label"            // "COST PER UNIT"
    const val inventory_cost_placeholder = "inventory_cost_placeholder" // "0.00"
    const val inventory_cost_required = "inventory_cost_required"       // why a positive cost is required (#101)
    const val inventory_condition_label = "inventory_condition_label"  // "CONDITION"
    const val inventory_condition_new = "inventory_condition_new"      // "New"
    const val inventory_condition_used = "inventory_condition_used"    // "Used"
    const val inventory_location_label = "inventory_location_label"   // "LOCATION"
    const val inventory_location_placeholder = "inventory_location_placeholder" // "Select location"

    // IMEI section
    const val inventory_imei_section = "inventory_imei_section"        // "ADD IMEIs"
    const val inventory_imei_label = "inventory_imei_label"            // "IMEI"
    const val inventory_imei_placeholder = "inventory_imei_placeholder" // "Scan or type IMEI"
    const val inventory_imei_add_cd = "inventory_imei_add_cd"          // "Add IMEI"
    const val inventory_imei_count = "inventory_imei_count"            // "{0} phones"
    const val inventory_imei_count_one = "inventory_imei_count_one"    // "1 phone"
    const val inventory_remove_unit_cd = "inventory_remove_unit_cd"    // "Remove unit"

    // IMEI check errors (inline, below the field)
    const val inventory_imei_error_invalid = "inventory_imei_error_invalid"     // "Not a valid IMEI"
    const val inventory_imei_error_in_batch = "inventory_imei_error_in_batch"   // "Already in this batch"
    const val inventory_imei_error_in_stock = "inventory_imei_error_in_stock"   // "Already in stock"
    const val inventory_imei_checking = "inventory_imei_checking"               // "Checking…"

    // Dropdown — add-new-inline
    const val inventory_dropdown_add_new = "inventory_dropdown_add_new"  // "+ Add \"{0}\""
    const val inventory_dropdown_no_results = "inventory_dropdown_no_results"  // "No results"
    const val inventory_dropdown_loading = "inventory_dropdown_loading"  // "Loading…"

    // Add-Inventory flow — Screen 2: review
    const val inventory_review_title = "inventory_review_title"            // "Review"
    const val inventory_review_unit_count = "inventory_review_unit_count"  // "{0} phones to add"
    const val inventory_review_unit_count_one = "inventory_review_unit_count_one" // "1 phone to add"
    const val inventory_review_edit_cd = "inventory_review_edit_cd"        // "Edit unit"
    const val inventory_review_delete_cd = "inventory_review_delete_cd"    // "Delete unit"
    const val inventory_review_add_more = "inventory_review_add_more"      // "+ Add more"
    const val inventory_confirm_btn = "inventory_confirm_btn"              // "Confirm"
    const val inventory_saving = "inventory_saving"                        // "Saving…"

    // Review save errors
    const val inventory_save_error_duplicate = "inventory_save_error_duplicate"
    const val inventory_save_error_network = "inventory_save_error_network"

    // Edit-unit dialog
    const val inventory_edit_unit_title = "inventory_edit_unit_title"      // "Edit unit"
    const val inventory_dialog_save = "inventory_dialog_save"              // "Save"
    const val inventory_dialog_cancel = "inventory_dialog_cancel"          // "Cancel"

    // Unsaved-changes guard dialog
    const val inventory_discard_title = "inventory_discard_title"          // "Discard changes?"
    const val inventory_discard_body = "inventory_discard_body"            // body text
    const val inventory_discard_confirm = "inventory_discard_confirm"      // "Discard"
    const val inventory_discard_cancel = "inventory_discard_cancel"        // "Keep editing"

    // Permission / no-access
    const val inventory_no_access = "inventory_no_access"                  // "You don't have permission to add inventory."

    // Column headers (desktop review table)
    const val inventory_col_imei = "inventory_col_imei"        // "IMEI"
    const val inventory_col_cost = "inventory_col_cost"        // "COST"
    const val inventory_col_condition = "inventory_col_condition" // "CONDITION"
    const val inventory_col_location = "inventory_col_location"  // "LOCATION"
    const val inventory_col_actions = "inventory_col_actions"    // "ACTIONS"
    const val inventory_col_capacity = "inventory_col_capacity"  // "CAP." (desktop review table)
    const val inventory_col_sell_price = "inventory_col_sell_price" // "SELL PRICE" (desktop review table)

    // ── SICKW paste — bulk iPhone intake (ticket #53) ────────────────────────────
    // Entry point + paste screen
    const val inventory_paste_button = "inventory_paste_button"        // "Paste from SICKW"
    const val inventory_paste_title = "inventory_paste_title"          // "Paste from SICKW"
    const val inventory_paste_subtitle = "inventory_paste_subtitle"    // helper under the title
    const val inventory_paste_hint = "inventory_paste_hint"            // multiline field placeholder
    const val inventory_paste_parse = "inventory_paste_parse"          // "Parse & add"
    const val inventory_paste_more = "inventory_paste_more"            // "Paste more"
    const val inventory_paste_clear = "inventory_paste_clear"          // "Clear"
    const val inventory_paste_empty = "inventory_paste_empty"          // "Paste SICKW text above to begin"

    // Parse summary banner — {0}=parsed count, {1}=unreadable count
    const val inventory_parse_summary = "inventory_parse_summary"              // "Parsed {0} phones · {1} couldn't be read"
    const val inventory_parse_summary_one = "inventory_parse_summary_one"      // "Parsed {0} phones"
    const val inventory_parse_none = "inventory_parse_none"                    // "Nothing could be read from that text"

    // Apply-to-all bar
    const val inventory_apply_all_title = "inventory_apply_all_title"    // "Apply to all"
    const val inventory_apply_all_hint = "inventory_apply_all_hint"      // "Set once, fills every row (override per row)"
    const val inventory_apply_all_action = "inventory_apply_all_action"  // "Apply to all rows"

    // Per-row / per-cell status
    const val inventory_status_parsed = "inventory_status_parsed"        // "From SICKW"
    const val inventory_status_must_fill = "inventory_status_must_fill"  // "Needs details"
    const val inventory_status_problem = "inventory_status_problem"      // "Problem"
    const val inventory_status_new_tag = "inventory_status_new_tag"      // "new"
    const val inventory_status_dup_batch = "inventory_status_dup_batch"  // "Duplicate in this paste"
    const val inventory_status_in_stock = "inventory_status_in_stock"    // "Already in stock"

    // Couldn't-read list
    const val inventory_unreadable_title = "inventory_unreadable_title"          // "Couldn't read"
    const val inventory_unreadable_count = "inventory_unreadable_count"          // "{0} blocks couldn't be read"
    const val inventory_unreadable_no_model = "inventory_unreadable_no_model"    // "No phone model found"
    const val inventory_unreadable_not_iphone = "inventory_unreadable_not_iphone" // "Not an iPhone — add it manually"
    const val inventory_unreadable_no_imei = "inventory_unreadable_no_imei"      // "No valid IMEI found"
    const val inventory_unreadable_dismiss = "inventory_unreadable_dismiss"      // "Dismiss"

    // Batch-size cap — {0}=count, {1}=ceiling
    const val inventory_batch_cap_title = "inventory_batch_cap_title"    // "Too many to add at once"
    const val inventory_batch_cap_body = "inventory_batch_cap_body"      // "This batch has {0} phones. The max per save is {1}…"

    // ── Browse inventory — ticket #55 (desktop two-pane browse) ─────────────────
    const val inventory_browse_all = "inventory_browse_all"              // "All"
    const val inventory_browse_locations = "inventory_browse_locations"  // "LOCATIONS"
    const val inventory_browse_empty_all = "inventory_browse_empty_all"  // "No inventory in stock"
    const val inventory_browse_empty_location = "inventory_browse_empty_location" // "No stock at this location"
    const val inventory_browse_in_stock = "inventory_browse_in_stock"    // "In Stock"
    const val inventory_browse_search = "inventory_browse_search"        // "Search brand / model / IMEI…"
    const val inventory_col_status = "inventory_col_status"              // "STATUS"
    const val inventory_col_qty = "inventory_col_qty"                    // "QTY"

    // ── Purchase dialog — record purchase to Humble Ledger (ticket #58) ──────────
    const val inventory_purchase_eyebrow = "inventory_purchase_eyebrow"            // "PURCHASE"
    const val inventory_purchase_title = "inventory_purchase_title"                // "Record this purchase"
    const val inventory_purchase_total = "inventory_purchase_total"                // "Batch total: {0}"
    const val inventory_purchase_bought_from = "inventory_purchase_bought_from"    // "BOUGHT FROM"
    const val inventory_purchase_bought_from_hint = "inventory_purchase_bought_from_hint" // "Unspecified Supplier"
    const val inventory_purchase_cash = "inventory_purchase_cash"                  // "CASH PAID NOW ({0})"
    const val inventory_purchase_bank = "inventory_purchase_bank"                  // "BANK PAID NOW ({0})"
    const val inventory_purchase_exceeds = "inventory_purchase_exceeds"            // "Paid amount can't exceed the batch total of {0}"
    const val inventory_purchase_confirm = "inventory_purchase_confirm"            // "Confirm"

    // ── Commission on intake (ticket #97) ────────────────────────────────────────
    // Intake dialog section
    const val commission_section_title = "commission_section_title"                // "Commission"
    const val commission_reach_per_unit = "commission_reach_per_unit"              // "{0} — {1} phones at {2} × {3}"
    const val commission_reach_percent = "commission_reach_percent"                // "{0} — {1} of {2} cost at {3}"
    const val commission_accrue = "commission_accrue"                              // "Add to balance"
    const val commission_pay_now = "commission_pay_now"                            // "Give now"
    const val commission_method_cash = "commission_method_cash"                    // "Cash"
    const val commission_method_bank = "commission_method_bank"                    // "Bank"
    const val commission_amount_label = "commission_amount_label"                  // "AMOUNT ({0})"
    const val commission_overridden = "commission_overridden"                      // "Edited"
    const val commission_skip = "commission_skip"                                  // "Skip"
    const val commission_skipped = "commission_skipped"                            // "Skipped — nothing owed"
    const val commission_undo_skip = "commission_undo_skip"                        // "Undo"
    // Give-now split (cash + bank) — mirrors the inventory-supplier UI
    const val commission_give_owed = "commission_give_owed"                        // "To give: {0}"
    const val commission_cash_field = "commission_cash_field"                      // "CASH ({0})"
    const val commission_bank_field = "commission_bank_field"                      // "BANK ({0})"
    const val commission_giving_now = "commission_giving_now"                      // "Giving now: {0}"
    const val commission_left_on_balance = "commission_left_on_balance"            // "Left on balance: {0}"
    const val commission_give_exceeds = "commission_give_exceeds"                  // "Given now can't exceed {0}"
    const val commission_section_supplier = "commission_section_supplier"          // "SUPPLIER"
    // Intake dialog section headers (distinguish supplier vs commission)
    const val commission_add_location = "commission_add_location"                  // "Add \"{0}\""
    const val commission_add_payee = "commission_add_payee"                        // "Add \"{0}\""
    // Rules management screen (admin)
    const val commission_rules_sidebar = "commission_rules_sidebar"                // "Commission"
    const val commission_rules_eyebrow = "commission_rules_eyebrow"                // "SETTINGS"
    const val commission_rules_title = "commission_rules_title"                    // "Commission rules"
    const val commission_rules_subtitle = "commission_rules_subtitle"              // "Pay a party per phone added to a location."
    const val commission_rules_add = "commission_rules_add"                        // "Add rule"
    const val commission_rules_empty = "commission_rules_empty"                    // "No commission rules yet"
    const val commission_rules_no_access = "commission_rules_no_access"            // "Only an admin can manage commission rules."
    const val commission_rules_col_location = "commission_rules_col_location"      // "LOCATION"
    const val commission_rules_col_payee = "commission_rules_col_payee"            // "PAYEE"
    const val commission_rules_col_rate = "commission_rules_col_rate"              // "RATE"
    const val commission_rules_col_status = "commission_rules_col_status"          // "STATUS"
    const val commission_rules_col_actions = "commission_rules_col_actions"        // ""
    const val commission_rule_active = "commission_rule_active"                    // "Active"
    const val commission_rule_off = "commission_rule_off"                          // "Off"
    const val commission_rule_edit = "commission_rule_edit"                        // "Edit"
    const val commission_rule_switch_off = "commission_rule_switch_off"            // "Switch off"
    const val commission_rate_per_unit = "commission_rate_per_unit"                // "Per phone"
    const val commission_rate_percent = "commission_rate_percent"                  // "% of cost"
    const val commission_rate_per_unit_value = "commission_rate_per_unit_value"    // "{0} / phone"
    const val commission_rate_percent_value = "commission_rate_percent_value"      // "{0} of cost"
    // Add/edit rule dialog
    const val commission_rule_dialog_add = "commission_rule_dialog_add"            // "New commission rule"
    const val commission_rule_dialog_edit = "commission_rule_dialog_edit"          // "Edit commission rule"
    const val commission_rule_field_location = "commission_rule_field_location"    // "LOCATION"
    const val commission_rule_field_location_hint = "commission_rule_field_location_hint" // "Choose a location"
    const val commission_rule_field_payee = "commission_rule_field_payee"          // "PAYEE"
    const val commission_rule_field_payee_hint = "commission_rule_field_payee_hint" // "Choose a payee"
    const val commission_rule_field_kind = "commission_rule_field_kind"            // "RATE TYPE"
    const val commission_rule_field_rate = "commission_rule_field_rate"            // "RATE"
    const val commission_rule_rate_hint_per_unit = "commission_rule_rate_hint_per_unit" // "Amount per phone, e.g. 5.00"
    const val commission_rule_rate_hint_percent = "commission_rule_rate_hint_percent"   // "Percent, e.g. 2 for 2%"
    const val commission_rule_save = "commission_rule_save"                        // "Save rule"
    const val commission_rule_cancel = "commission_rule_cancel"                    // "Cancel"
    const val commission_rule_close_cd = "commission_rule_close_cd"                // "Close"

    // ── Sales — ticket #63 (desktop counter screen) ──────────────────────────────
    const val sales_no_access = "sales_no_access"                        // "You don't have access to Sales."
    const val sales_close_cd = "sales_close_cd"                          // "Close"

    // Cart (left pane)
    const val sales_cart_title = "sales_cart_title"                      // "Cart"
    const val sales_cart_add_phone = "sales_cart_add_phone"              // "+ Add phone"
    const val sales_cart_add_item = "sales_cart_add_item"                // "+ Item"
    const val sales_cart_empty_title = "sales_cart_empty_title"          // "Cart is empty"
    const val sales_cart_empty_body = "sales_cart_empty_body"            // "Add a phone or a custom item to start a sale"
    const val sales_cart_col_price = "sales_cart_col_price"              // "PRICE"
    const val sales_cart_col_discount = "sales_cart_col_discount"        // "DISCOUNT"
    const val sales_cart_col_net = "sales_cart_col_net"                  // "NET"
    const val sales_cart_remove_cd = "sales_cart_remove_cd"              // "Remove"
    const val sales_cart_sale_discount_label = "sales_cart_sale_discount_label" // "WHOLE-SALE DISCOUNT"
    const val sales_cart_line_discount_error = "sales_cart_line_discount_error" // "Discount can't exceed the item's price"

    // Checkout (right pane)
    const val sales_checkout_title = "sales_checkout_title"              // "Checkout"
    const val sales_checkout_customer_label = "sales_checkout_customer_label" // "CUSTOMER"
    const val sales_checkout_customer_placeholder = "sales_checkout_customer_placeholder" // "Search or select a customer…"
    const val sales_checkout_walk_in_button = "sales_checkout_walk_in_button" // "Walk-in Customer"
    const val sales_checkout_cash = "sales_checkout_cash"                // "CASH ({0})"
    const val sales_checkout_card = "sales_checkout_card"                // "CARD ({0})"
    const val sales_checkout_bank = "sales_checkout_bank"                // "BANK ({0})"
    const val sales_checkout_note_label = "sales_checkout_note_label"    // "NOTE (OPTIONAL)"
    const val sales_checkout_note_placeholder = "sales_checkout_note_placeholder" // "Add a note about this sale…"
    // Tax-inclusive pricing toggle (ticket #106) — per sale, resets to off on each new sale.
    const val sales_checkout_tax_inclusive_label = "sales_checkout_tax_inclusive_label" // "Prices include tax"
    // Customer tax number at checkout (ticket #106 follow-up): editable field + "Save to contact".
    const val sales_checkout_buyer_tax_number_label = "sales_checkout_buyer_tax_number_label" // "CUSTOMER TAX NUMBER (OPTIONAL)"
    const val sales_checkout_buyer_tax_number_placeholder = "sales_checkout_buyer_tax_number_placeholder" // "GST/HST number for this invoice"
    const val sales_action_save_tax_to_contact = "sales_action_save_tax_to_contact" // "Save to contact"
    const val sales_tax_saved_to_contact = "sales_tax_saved_to_contact"       // "Saved to contact"
    const val sales_tax_save_error = "sales_tax_save_error"                   // "Couldn't save to contact — try again"

    // Walk-in buyer capture (ticket #77) — shown only when the buyer is the Walk-in party
    const val sales_buyer_name_label = "sales_buyer_name_label"          // "NAME FOR INVOICE (OPTIONAL)"
    const val sales_buyer_name_placeholder = "sales_buyer_name_placeholder" // "e.g. John Smith — leave blank for Walk-in Customer"
    const val sales_buyer_phone_label = "sales_buyer_phone_label"        // "PHONE (OPTIONAL)"
    const val sales_buyer_phone_placeholder = "sales_buyer_phone_placeholder" // "Contact number for the invoice"

    // Totals card
    const val sales_totals_subtotal = "sales_totals_subtotal"            // "Subtotal"
    const val sales_totals_grand_total = "sales_totals_grand_total"      // "Total"
    const val sales_totals_paid = "sales_totals_paid"                    // "Paid"
    const val sales_totals_balance = "sales_totals_balance"              // "Balance"

    // Gating hints
    const val sales_error_empty_cart = "sales_error_empty_cart"          // "Add at least one item to the cart"
    const val sales_error_no_customer = "sales_error_no_customer"        // "Select a customer or Walk-in"
    const val sales_error_sale_discount = "sales_error_sale_discount"    // "Discount can't exceed the subtotal"
    const val sales_error_overpayment = "sales_error_overpayment"        // "Amount paid can't exceed the total"
    const val sales_error_walk_in_full = "sales_error_walk_in_full"      // "A walk-in sale must be paid in full"

    const val sales_confirm_button = "sales_confirm_button"              // "Confirm sale"
    const val sales_confirm_submitting = "sales_confirm_submitting"      // "Recording sale…"

    // Item picker modal
    const val sales_picker_title = "sales_picker_title"                  // "Add a phone"
    const val sales_picker_search = "sales_picker_search"                // "Search brand / model / IMEI…"
    const val sales_picker_empty = "sales_picker_empty"                  // "No in-stock units match"
    const val sales_picker_added_cd = "sales_picker_added_cd"            // "Added"
    const val sales_picker_add_cd = "sales_picker_add_cd"                // "Add to cart"
    const val sales_picker_done = "sales_picker_done"                    // "Done"
    const val sales_picker_all_brands = "sales_picker_all_brands"        // "All brands"
    const val sales_picker_select_brand = "sales_picker_select_brand"    // "Select a brand"
    const val sales_picker_models = "sales_picker_models"                // "models"
    const val sales_picker_in_stock = "sales_picker_in_stock"            // "in stock"
    const val sales_picker_in_sale = "sales_picker_in_sale"              // "in this sale"
    const val sales_picker_location = "sales_picker_location"            // "Location"
    const val sales_picker_back_cd = "sales_picker_back_cd"              // "Back"
    const val sales_picker_show_all_locations = "sales_picker_show_all_locations" // "Show all locations"
    const val sales_picker_none_here = "sales_picker_none_here"          // "Nothing in stock here"
    const val sales_picker_results = "sales_picker_results"              // "Search results"
    const val sales_picker_phones = "sales_picker_phones"                // "phones"
    const val sales_picker_brands = "sales_picker_brands"                // "brands"
    const val sales_picker_across = "sales_picker_across"                // "across"
    const val sales_picker_at = "sales_picker_at"                        // "at"
    const val sales_picker_units_match = "sales_picker_units_match"      // "units match"
    const val sales_picker_phone_one = "sales_picker_phone_one"          // "phone"
    const val sales_picker_brand_one = "sales_picker_brand_one"          // "brand"
    const val sales_picker_model_one = "sales_picker_model_one"          // "model"
    const val sales_picker_unit_one = "sales_picker_unit_one"            // "unit matches"
    const val sales_picker_col_phone = "sales_picker_col_phone"          // "PHONE"
    const val sales_picker_col_imei = "sales_picker_col_imei"            // "IMEI"
    const val sales_picker_col_capacity = "sales_picker_col_capacity"    // "CAPACITY"
    const val sales_picker_col_colour = "sales_picker_col_colour"        // "COLOUR"
    const val sales_picker_col_location = "sales_picker_col_location"    // "LOCATION"
    const val sales_picker_col_condition = "sales_picker_col_condition"  // "CONDITION"
    const val sales_picker_col_price = "sales_picker_col_price"          // "PRICE"
    const val sales_picker_col_carrier = "sales_picker_col_carrier"    // "CARRIER"
    const val sales_picker_col_cost = "sales_picker_col_cost"            // "COST"
    const val sales_picker_remove_cd = "sales_picker_remove_cd"          // "Remove"

    // Custom line dialog
    const val sales_custom_title = "sales_custom_title"                  // "Add item"
    const val sales_custom_name_label = "sales_custom_name_label"        // "NAME"
    const val sales_custom_name_placeholder = "sales_custom_name_placeholder" // "e.g. Phone case"
    const val sales_custom_price_label = "sales_custom_price_label"      // "PRICE ({0})"
    const val sales_custom_add = "sales_custom_add"                      // "Add"
    const val sales_custom_cancel = "sales_custom_cancel"                // "Cancel"

    // Confirm outcomes
    const val sales_success_eyebrow = "sales_success_eyebrow"            // "SALE COMPLETE"
    const val sales_success_title = "sales_success_title"                // "Sale recorded"
    const val sales_success_customer = "sales_success_customer"          // "Customer"
    const val sales_success_items = "sales_success_items"                // "Items"
    const val sales_success_total = "sales_success_total"                // "Total"
    const val sales_success_paid = "sales_success_paid"                  // "Paid"
    const val sales_success_balance = "sales_success_balance"            // "Balance"
    const val sales_success_new_sale = "sales_success_new_sale"          // "New sale"
    // The sale-complete dialog's dismiss button (ticket #106): reads "Done" but still starts a new
    // sale (clears the cart) so the previous customer's cart never carries into the next.
    const val sales_success_done = "sales_success_done"                  // "Done"

    // Invoice row on the Sale-complete screen (ticket #77)
    const val sales_invoice_label = "sales_invoice_label"                // "Invoice"
    const val sales_invoice_preparing = "sales_invoice_preparing"        // "Preparing invoice…"
    const val sales_invoice_view = "sales_invoice_view"                  // "View"
    const val sales_invoice_print = "sales_invoice_print"                // "Print"
    const val sales_invoice_copy = "sales_invoice_copy"                  // "Copy link"
    const val sales_invoice_copied = "sales_invoice_copied"              // "Link copied"
    const val sales_invoice_share = "sales_invoice_share"                // "Share"
    const val sales_invoice_open = "sales_invoice_open"                  // "Open"
    const val sales_invoice_failed = "sales_invoice_failed"              // "Invoice is still being prepared — it'll appear here shortly."
    const val sales_invoice_retry = "sales_invoice_retry"                // "Retry"
    const val sales_invoice_retrying = "sales_invoice_retrying"          // "Retrying…"
    const val sales_invoice_retry_error = "sales_invoice_retry_error"    // "Couldn't reach the invoice service — it'll retry automatically."

    const val sales_already_sold_title = "sales_already_sold_title"      // "Already sold"
    const val sales_already_sold_body = "sales_already_sold_body"        // "{0} was just sold by another cashier — removed from the cart."
    const val sales_already_sold_dismiss = "sales_already_sold_dismiss"  // "OK"

    const val sales_error_title = "sales_error_title"                    // "Couldn't complete sale"
    const val sales_error_dismiss = "sales_error_dismiss"                // "Dismiss"
    const val sales_error_add_customer_generic = "sales_error_add_customer_generic" // "Could not add customer"

    // ── Money movement + statement (M8, tickets #90/#91) ─────────────────────
    const val money_title = "money_title"                             // "Money"
    const val money_subtitle = "money_subtitle"                       // "Record money moving between accounts"
    const val money_from = "money_from"                               // "From"
    const val money_to = "money_to"                                   // "To"
    const val money_swap = "money_swap"                               // "Swap"
    const val money_amount = "money_amount"                           // "Amount"
    const val money_note = "money_note"                               // "Note (optional)"
    const val money_date = "money_date"                               // "Date"
    const val money_record = "money_record"                           // "Record"
    const val money_recording = "money_recording"                     // "Recording…"
    const val money_recorded = "money_recorded"                       // "Recorded"
    const val money_pick_account = "money_pick_account"               // "Choose an account"
    const val money_search_accounts = "money_search_accounts"         // "Search parties, Cash or Bank"
    const val money_own_accounts = "money_own_accounts"               // "Your accounts"
    const val money_parties = "money_parties"                         // "Parties"
    const val money_err_same_account = "money_err_same_account"       // "From and To must be different accounts"
    const val money_err_amount = "money_err_amount"                   // "Enter an amount greater than zero"
    const val money_err_note_long = "money_err_note_long"             // "That note is too long"
    const val money_recent = "money_recent"                           // "Recent entries"
    const val money_empty = "money_empty"                             // "No money movements yet"
    const val money_empty_hint = "money_empty_hint"                   // "Record a payment, a payout, or money lent — it appears here."
    const val money_reverse = "money_reverse"                         // "Reverse"
    const val money_reversing = "money_reversing"                     // "Reversing…"
    const val money_reversed_badge = "money_reversed_badge"           // "Reversed"
    const val money_reversal_badge = "money_reversal_badge"           // "Reversal"
    const val money_sync_pending = "money_sync_pending"               // "Sending to ledger…"
    const val money_sync_failed = "money_sync_failed"                 // "Not in the ledger yet — it'll retry automatically"
    const val money_no_access = "money_no_access"                     // "You don't have access to transactions"
    const val money_balance_owes = "money_balance_owes"               // "owes"
    const val money_balance_owed = "money_balance_owed"               // "you owe"
    const val money_balance_settled = "money_balance_settled"         // "settled"
    const val money_balance_unknown = "money_balance_unknown"         // "balance unavailable"
    const val money_refresh_balances = "money_refresh_balances"       // "Refresh balances"
    const val money_statement = "money_statement"                     // "Statement"
    const val money_statement_empty = "money_statement_empty"         // "No ledger activity yet"
    const val money_statement_range_empty = "money_statement_range_empty"// "No activity in this date range"
    const val money_statement_debit = "money_statement_debit"         // "Debit"
    const val money_statement_credit = "money_statement_credit"       // "Credit"
    const val money_statement_balance = "money_statement_balance"     // "Balance"
    const val money_statement_from = "money_statement_from"           // "From"
    const val money_statement_to = "money_statement_to"               // "To"
    const val money_statement_load_more = "money_statement_load_more" // "Load more"
    const val money_statement_unavailable = "money_statement_unavailable"// "Couldn't load the statement from the ledger"
    const val money_statement_closing = "money_statement_closing"     // "Closing balance"
    const val money_parties_unavailable = "money_parties_unavailable" // "You can move money between Cash and Bank, but you don't have access to parties."
    const val money_col_date = "money_col_date"                       // "Date"
    const val money_col_note = "money_col_note"                       // "Note"
    const val money_col_status = "money_col_status"                   // "Status"
    const val money_note_placeholder = "money_note_placeholder"       // "What was this for?"
    const val money_no_matches = "money_no_matches"                   // "No matching account"
    const val money_sync_ok = "money_sync_ok"                         // "In ledger"
    const val money_sync_failed_short = "money_sync_failed_short"     // "Not posted"
    const val money_col_particulars = "money_col_particulars"         // "Particulars"
    const val money_cancel = "money_cancel"                           // "Cancel"
    const val money_reverse_title = "money_reverse_title"             // "Reverse this entry?"
    const val money_reverse_body = "money_reverse_body"               // "This posts {amount} back from {to} to {from}. The or"
    const val money_empty_filtered = "money_empty_filtered"           // "No entries match"
    const val money_empty_filtered_hint = "money_empty_filtered_hint" // "Try a different search or date range."
    const val money_of = "money_of"                                   // "of"
    const val money_clear_filters = "money_clear_filters"             // "Clear"
    const val money_search_placeholder = "money_search_placeholder"   // "Search party, note or amount"
    const val money_range_all = "money_range_all"                     // "All dates"
    const val money_range_today = "money_range_today"                 // "Today"
    const val money_range_7 = "money_range_7"                         // "Last 7 days"
    const val money_range_30 = "money_range_30"                       // "Last 30 days"
    const val money_range_month = "money_range_month"                 // "This month"
    const val money_range_from = "money_range_from"                   // "From"
    const val money_range_to = "money_range_to"                       // "To"
    const val money_range_since = "money_range_since"                 // "Since"
    const val money_range_until = "money_range_until"                 // "Until"
    const val money_range_apply = "money_range_apply"                 // "Apply"
    const val money_posting_payment = "money_posting_payment"         // "Payment received"
    const val money_posting_payout = "money_posting_payout"           // "Payment made"
    const val money_posting_purchase = "money_posting_purchase"       // "Purchase"
    const val money_posting_expense = "money_posting_expense"         // "Expense"
    const val money_posting_refund = "money_posting_refund"           // "Refund"
    // ── Print statement (ticket #109) ─────────────────────────────────────────────
    const val statement_print = "statement_print"                     // "Print statement"
    const val statement_print_title = "statement_print_title"         // "Print statement"
    const val statement_range = "statement_range"                     // "Date range"
    const val statement_include_notes = "statement_include_notes"     // "Include notes"
    const val statement_include_notes_hint = "statement_include_notes_hint" // "Adds the note typed on each entry. Off for a customer copy."
    const val statement_generate = "statement_generate"               // "Generate"
    const val statement_generating = "statement_generating"           // "Generating…"
    const val statement_error_generic = "statement_error_generic"     // "Couldn't generate the statement. Try again."
    const val statement_too_large = "statement_too_large"             // "This period has more than 2000 entries — narrow the date range."
    const val statement_open_pdf = "statement_open_pdf"               // "Open PDF"
    const val statement_share = "statement_share"                     // "Share"
    const val statement_download = "statement_download"               // "Download"
    // ── Sales History (ticket #83) ────────────────────────────────────────────────
    const val sales_history_title = "sales_history_title"
    const val sales_history_subtitle = "sales_history_subtitle"
    const val sales_history_recent = "sales_history_recent"                // "Sales history"
    const val sales_history_sidebar = "sales_history_sidebar"            // "Sales History"
    const val sales_history_search_placeholder = "sales_history_search_placeholder" // "Search customer, invoice #, or IMEI…"
    const val sales_history_filters = "sales_history_filters"            // "Filters"
    const val sales_history_filters_active = "sales_history_filters_active" // "Filters ({0})"
    const val sales_history_no_access = "sales_history_no_access"         // "You don't have access to Sales."
    const val sales_history_empty_title = "sales_history_empty_title"     // "No sales yet"
    const val sales_history_empty_body = "sales_history_empty_body"       // "Completed sales will appear here."
    const val sales_history_no_match_title = "sales_history_no_match_title" // "No sales match your filters"
    const val sales_history_no_match_body = "sales_history_no_match_body" // "Try a different search or clear the filters."
    const val sales_history_loading_more = "sales_history_loading_more"   // "Loading more…"
    const val sales_history_error = "sales_history_error"                 // "Couldn't load sales"

    // History table columns
    const val sales_history_col_date = "sales_history_col_date"          // "DATE"
    const val sales_history_col_invoice = "sales_history_col_invoice"    // "INVOICE #"
    const val sales_history_col_customer = "sales_history_col_customer"  // "CUSTOMER"
    const val sales_history_col_items = "sales_history_col_items"        // "ITEMS"
    const val sales_history_col_imei = "sales_history_col_imei"          // "IMEI"
    const val sales_history_col_total = "sales_history_col_total"        // "TOTAL"
    const val sales_history_col_paid = "sales_history_col_paid"          // "PAID"
    const val sales_history_col_balance = "sales_history_col_balance"    // "BALANCE"
    const val sales_history_col_status = "sales_history_col_status"      // "STATUS"
    const val sales_history_col_actions = "sales_history_col_actions"    // "INVOICE"
    const val sales_history_items_more = "sales_history_items_more"      // "+{0} more"
    const val sales_history_no_invoice = "sales_history_no_invoice"      // "No invoice number"

    // Sync / invoice status chips
    const val sales_history_sync_pending = "sales_history_sync_pending"  // "Sync pending"
    const val sales_history_sync_synced = "sales_history_sync_synced"    // "Synced"
    const val sales_history_sync_failed = "sales_history_sync_failed"    // "Sync failed"
    const val sales_history_inv_pending = "sales_history_inv_pending"    // "Invoice pending"
    const val sales_history_inv_issued = "sales_history_inv_issued"      // "Invoiced"
    const val sales_history_inv_failed = "sales_history_inv_failed"      // "Invoice failed"

    // Filter dialog
    const val sales_history_filter_title = "sales_history_filter_title"  // "Filter sales"
    const val sales_history_filter_customer = "sales_history_filter_customer" // "CUSTOMER NAME"
    const val sales_history_filter_customer_ph = "sales_history_filter_customer_ph" // "Search by customer name"
    const val sales_history_filter_imei = "sales_history_filter_imei"    // "IMEI / SERIAL"
    const val sales_history_filter_imei_ph = "sales_history_filter_imei_ph" // "Exact IMEI"
    const val sales_history_filter_invoice = "sales_history_filter_invoice" // "INVOICE NUMBER"
    const val sales_history_filter_invoice_ph = "sales_history_filter_invoice_ph" // "e.g. INV-000042"
    const val sales_history_filter_date_from = "sales_history_filter_date_from" // "FROM (YYYY-MM-DD)"
    const val sales_history_filter_date_to = "sales_history_filter_date_to" // "TO (YYYY-MM-DD)"
    const val sales_history_filter_balance = "sales_history_filter_balance" // "Only sales with a balance"
    const val sales_history_filter_apply = "sales_history_filter_apply"  // "Apply filters"
    const val sales_history_filter_clear = "sales_history_filter_clear"  // "Clear all"
    const val sales_history_filter_cancel = "sales_history_filter_cancel" // "Cancel"
    const val sales_history_filter_date_error = "sales_history_filter_date_error" // "Use the format YYYY-MM-DD"

    // Detail view
    const val sales_history_detail_title = "sales_history_detail_title"  // "Sale details"
    const val sales_history_detail_back = "sales_history_detail_back"    // "Back to sales"
    const val sales_history_detail_customer = "sales_history_detail_customer" // "Customer"
    const val sales_history_detail_buyer = "sales_history_detail_buyer"  // "Buyer"
    const val sales_history_detail_buyer_phone = "sales_history_detail_buyer_phone" // "Buyer phone"
    const val sales_history_detail_sold_by = "sales_history_detail_sold_by" // "Sold by"
    const val sales_history_detail_sold_at = "sales_history_detail_sold_at" // "Date"
    const val sales_history_detail_items = "sales_history_detail_items"  // "Items"
    const val sales_history_detail_payments = "sales_history_detail_payments" // "Payment"
    const val sales_history_detail_note = "sales_history_detail_note"    // "Note"
    const val sales_history_detail_sale_discount = "sales_history_detail_sale_discount" // "Sale discount"
    const val sales_history_detail_line_list = "sales_history_detail_line_list" // "List"
    const val sales_history_detail_line_unit = "sales_history_detail_line_unit" // "Unit"
    const val sales_history_detail_line_discount = "sales_history_detail_line_discount" // "Discount"
    const val sales_history_detail_line_net = "sales_history_detail_line_net" // "Net"

    // Bill / invoice layout (ticket #83)
    const val sales_history_bill_eyebrow = "sales_history_bill_eyebrow"  // "INVOICE"
    const val sales_history_bill_to = "sales_history_bill_to"            // "BILL TO"
    const val sales_history_col_item = "sales_history_col_item"          // "ITEM"
    const val sales_history_col_amount = "sales_history_col_amount"      // "AMOUNT"

    // Detail top-bar PDF actions (ticket #83 follow-up)
    const val sales_history_share = "sales_history_share"                // "Share"
    const val sales_history_download = "sales_history_download"          // "Download"
    const val sales_history_pdf_saved = "sales_history_pdf_saved"        // "Invoice saved"
    const val sales_history_pdf_error = "sales_history_pdf_error"        // "Couldn't save the invoice"

    // Phone smart-search interpretation hint (ticket #84) — which reading the single search box
    // used, so a surprising full-history result is explainable.
    const val sales_history_searched_by_imei = "sales_history_searched_by_imei"        // "Searched by IMEI"
    const val sales_history_searched_by_invoice = "sales_history_searched_by_invoice"  // "Searched by invoice number"
    const val sales_history_searched_by_customer = "sales_history_searched_by_customer" // "Searched by customer name"

    // Void a sale (ticket #85) — admin-only reversal on the detail view.
    const val sales_history_void = "sales_history_void"                              // "Void sale"
    const val sales_history_voided_badge = "sales_history_voided_badge"              // "VOIDED"
    const val sales_history_void_dialog_title = "sales_history_void_dialog_title"    // "Void this sale?"
    const val sales_history_void_dialog_body = "sales_history_void_dialog_body"      // what gets reversed
    const val sales_history_void_reason_label = "sales_history_void_reason_label"    // "Reason (required)"
    const val sales_history_void_reason_ph = "sales_history_void_reason_ph"          // "e.g. rung up by mistake"
    const val sales_history_void_confirm = "sales_history_void_confirm"              // "Void sale"
    const val sales_history_void_cancel = "sales_history_void_cancel"                // "Cancel"
    const val sales_history_void_in_progress = "sales_history_void_in_progress"      // "Voiding…"
    const val sales_history_void_error = "sales_history_void_error"                  // generic failure
    const val sales_history_voided_note = "sales_history_voided_note"                // read-only banner on a voided sale
    const val sales_history_refresh = "sales_history_refresh"                        // "Refresh"
    const val entities_sort_label = "entities_sort_label"             // "Sort"
    const val entities_sort_name = "entities_sort_name"               // "A–Z"
    const val entities_sort_balance = "entities_sort_balance"         // "Amount"
    const val balances_just_now = "balances_just_now"                 // "Updated just now"
    const val balances_minutes_ago = "balances_minutes_ago"           // "Updated {0}m ago"
    const val balances_hours_ago = "balances_hours_ago"               // "Updated {0}h ago"
    const val balances_updating = "balances_updating"                 // "Updating…"
    const val balances_not_loaded = "balances_not_loaded"             // "Balances not loaded"
    const val backend_error_auth = "backend_error_auth"               // "Your session expired. We refreshed it — plea"
    const val backend_error_unreachable = "backend_error_unreachable" // "Couldn't reach the server. Nothing was saved"
    const val money_charge_tag = "money_charge_tag"        // "Charge"
    // The sale/purchase value carried alongside a statement row whose money column shows only cash.
    const val money_event_sale_value = "money_event_sale_value"         // "Sale of {0}"
    const val money_event_purchase_value = "money_event_purchase_value" // "Purchase of {0}"
    const val money_event_billed_value = "money_event_billed_value"     // "Value {0}"
    const val money_event_nothing_paid = "money_event_nothing_paid"     // "No payment"
    const val money_col_money = "money_col_money"                       // "Money in / out"
    // Which way a statement balance runs. Words, not colour — green/red already mean cash in/out
    // in the money column next to it.
    const val money_balance_they_owe = "money_balance_they_owe"         // "they owe"
    const val money_balance_you_owe = "money_balance_you_owe"           // "you owe"
    const val settings_title = "settings_title"
    const val settings_subtitle = "settings_subtitle"
    const val settings_readonly = "settings_readonly"
    const val settings_tax_title = "settings_tax_title"
    const val settings_tax_subtitle = "settings_tax_subtitle"
    const val settings_gst = "settings_gst"
    const val settings_gst_rate = "settings_gst_rate"
    const val settings_hst_rate = "settings_hst_rate"
    const val settings_pst = "settings_pst"
    const val settings_pst_rate = "settings_pst_rate"
    const val settings_hst = "settings_hst"
    const val settings_hst_hint = "settings_hst_hint"
    const val settings_err_gst_rate = "settings_err_gst_rate"
    const val settings_err_pst_rate = "settings_err_pst_rate"
    const val settings_err_hst_needs_gst = "settings_err_hst_needs_gst"
    const val settings_warn_no_tax = "settings_warn_no_tax"
    const val settings_company_title = "settings_company_title"
    const val settings_company_subtitle = "settings_company_subtitle"
    const val settings_company_name = "settings_company_name"
    const val settings_legal_name = "settings_legal_name"
    const val settings_tax_number = "settings_tax_number"
    const val settings_address = "settings_address"
    const val settings_contact_email = "settings_contact_email"
    const val settings_contact_phone = "settings_contact_phone"
    const val settings_log_title = "settings_log_title"
    const val settings_log_subtitle = "settings_log_subtitle"
    const val settings_log_empty = "settings_log_empty"
    const val settings_save = "settings_save"
    const val settings_saving = "settings_saving"
    const val settings_saved = "settings_saved"
    const val settings_tax_confirm_title = "settings_tax_confirm_title"
    const val settings_tax_confirm_intro = "settings_tax_confirm_intro"
    const val settings_tax_confirm_past = "settings_tax_confirm_past"
    const val sales_cart_line_unpriced = "sales_cart_line_unpriced"

    // ── Stock history + per-unit delete (ticket #106) ──────────────────────────
    const val stock_history_title = "stock_history_title"
    const val stock_history_sidebar = "stock_history_sidebar"
    const val stock_history_subtitle = "stock_history_subtitle"
    const val stock_history_no_access = "stock_history_no_access"
    const val stock_history_recent = "stock_history_recent"
    const val stock_history_empty = "stock_history_empty"
    const val stock_history_empty_hint = "stock_history_empty_hint"
    const val stock_history_empty_filtered = "stock_history_empty_filtered"
    const val stock_history_empty_filtered_hint = "stock_history_empty_filtered_hint"
    const val stock_history_col_date = "stock_history_col_date"
    const val stock_history_col_party = "stock_history_col_party"
    const val stock_history_col_units = "stock_history_col_units"
    const val stock_history_col_cost = "stock_history_col_cost"
    const val stock_history_col_paid = "stock_history_col_paid"
    const val stock_history_col_owing = "stock_history_col_owing"
    const val stock_history_filter_active = "stock_history_filter_active"
    const val stock_history_filter_reversed = "stock_history_filter_reversed"
    const val stock_history_filter_all = "stock_history_filter_all"
    const val stock_history_reversed_tag = "stock_history_reversed_tag"
    const val stock_history_reversing_tag = "stock_history_reversing_tag"
    const val stock_history_reverse_failed_tag = "stock_history_reverse_failed_tag"
    const val stock_history_units_title = "stock_history_units_title"
    const val stock_history_units_empty = "stock_history_units_empty"
    const val stock_history_unit_sold = "stock_history_unit_sold"
    const val stock_history_unit_removed = "stock_history_unit_removed"
    const val stock_history_unit_in_stock = "stock_history_unit_in_stock"
    const val stock_history_load_more = "stock_history_load_more"
    const val stock_history_loading_more = "stock_history_loading_more"
    const val stock_history_window_hint = "stock_history_window_hint"
    const val sales_history_customers_capped = "sales_history_customers_capped"
    const val stock_history_reverse = "stock_history_reverse"
    const val stock_history_reverse_title = "stock_history_reverse_title"
    const val stock_history_reverse_body = "stock_history_reverse_body"
    const val stock_history_reverse_ledger = "stock_history_reverse_ledger"
    const val stock_history_reverse_refund = "stock_history_reverse_refund"
    const val stock_history_reverse_commission = "stock_history_reverse_commission"
    const val stock_history_reverse_reason = "stock_history_reverse_reason"
    const val stock_history_reverse_reason_hint = "stock_history_reverse_reason_hint"
    const val stock_history_reverse_confirm = "stock_history_reverse_confirm"
    const val stock_history_reverse_cancel = "stock_history_reverse_cancel"
    const val stock_history_block_sold = "stock_history_block_sold"
    const val stock_history_block_removed = "stock_history_block_removed"
    const val stock_history_block_unknown = "stock_history_block_unknown"
    const val stock_history_block_already = "stock_history_block_already"
    const val stock_history_block_inflight = "stock_history_block_inflight"
    const val stock_history_reversal_error = "stock_history_reversal_error"
    const val inventory_delete_unit = "inventory_delete_unit"
    const val inventory_delete_unit_title = "inventory_delete_unit_title"
    const val inventory_delete_unit_body = "inventory_delete_unit_body"
    const val inventory_delete_unit_ledger = "inventory_delete_unit_ledger"
    const val inventory_delete_unit_confirm = "inventory_delete_unit_confirm"
    const val inventory_delete_unit_cancel = "inventory_delete_unit_cancel"
    const val inventory_delete_unit_failed = "inventory_delete_unit_failed"
    const val statement_search_hint = "statement_search_hint"
    const val statement_sort_newest = "statement_sort_newest"
    const val statement_sort_oldest = "statement_sort_oldest"
    const val statement_search_scope = "statement_search_scope"
    const val sale_date_label = "sale_date_label"
    const val sale_date_backdated = "sale_date_backdated"
    const val purchase_date_label = "purchase_date_label"
    const val purchase_date_backdated = "purchase_date_backdated"
    const val sales_tax_changed = "sales_tax_changed"
    const val sales_tax_changed_none = "sales_tax_changed_none"
}
