import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.security.login.LoginManager
import com.onresolve.scriptrunner.canned.util.OutputFormatter
import java.text.SimpleDateFormat

// ============================================================
// ✏️  YOUR SETTINGS — THIS IS THE ONLY SECTION YOU NEED TO EDIT
// ============================================================

// How many days without a login counts as dormant?
// Example: 90 = anyone who hasn't logged in for 3 months will appear in the report.
def cutoffDays = 90

// Should users who have NEVER logged in appear in the report?
//   true  = yes, include them (recommended — they may still hold a licence)
//   false = no, skip them
def includeNeverLoggedIn = true

// Which Jira groups count as licenced?
// Leave this list EMPTY to scan ALL groups in your instance (recommended to start with).
// Add specific group names if you only want to report on certain licence groups.
// Example:
//   [] as Set<String>                          → scans everyone
//   ['jira-software-users'] as Set<String>     → only Jira Software users
def licensedGroups = [] as Set<String>

// ============================================================
// 🔧 SCRIPT ENGINE — DO NOT EDIT BELOW THIS LINE
// ============================================================

def userManager  = ComponentAccessor.userManager
def groupManager = ComponentAccessor.groupManager
def loginManager = ComponentAccessor.getComponent(LoginManager)
def sdf          = new SimpleDateFormat("yyyy-MM-dd HH:mm")
def reportTime   = sdf.format(new Date())

// Work out the exact cut-off point in time.
long cutoffMs = System.currentTimeMillis() - (cutoffDays * 24L * 60L * 60L * 1_000L)

// ── Pre-fetch static data once — before the loop ─────────────
// Fetching these inside the loop would mean thousands of identical
// database calls on large instances. We fetch them once and reuse.

// All group names in the instance — only fetched when licensedGroups is empty.
Collection<String> allGroupNames = licensedGroups.isEmpty()
    ? groupManager.getAllGroupNames()
    : licensedGroups

// Directory name cache — directories never change during a script run,
// so we look each one up once and store it here by its ID.
Map<Long, String> directoryCache = [:]

// Total user count — used only for progress logging.
int totalCount = userManager.getTotalUserCount()
int processed  = 0

// ── Scan every active, licenced, dormant user ─────────────────
List<Map<String, Object>> results = []

userManager.getAllApplicationUsers().each { user ->
    processed++

    // Log progress every 1000 users so you can confirm the script is
    // still running on large instances. Check Jira's application log.
    if (processed % 1000 == 0) {
        log.warn("Dormant user scan: ${processed} / ${totalCount} users processed...")
    }

    // Skip accounts that are already deactivated in Jira.
    if (!user.active) return

    // Skip users who are not in any licenced group.
    Collection<String> userGroups     = groupManager.getGroupNamesForUser(user)
    Collection<String> licencedGroups = userGroups.intersect(allGroupNames)
    if (licencedGroups.isEmpty()) return

    // Check when they last logged in.
    // lastLogin will be null if they have never logged in at all.
    def loginInfo  = loginManager.getLoginInfo(user.name)
    Long lastLogin = loginInfo?.lastLoginTime

    if (lastLogin == null && !includeNeverLoggedIn) return
    if (lastLogin != null && lastLogin >= cutoffMs) return

    // Look up the directory this user belongs to.
    // Each directory is fetched once and cached — not once per user.
    // "Internal" = you can deactivate it directly in Jira.
    // "LDAP / Active Directory" = deactivate it in your company directory instead.
    String directory = directoryCache.computeIfAbsent(user.directoryId) {
        userManager.getDirectory(it)?.name ?: 'Unknown'
    }

    results << ([
        username      : user.name,
        displayName   : user.displayName,
        email         : user.emailAddress ?: '',
        lastLogin     : lastLogin ? sdf.format(new Date(lastLogin)) : 'NEVER',
        lastLoginMs   : lastLogin ?: 0L,
        loginCount    : loginInfo?.loginCount ?: 0L,
        directory     : directory,
        licencedGroups: licencedGroups.sort().join('; '),
    ] as Map<String, Object>)
}

log.warn("Dormant user scan complete: ${results.size()} dormant users found from ${totalCount} total.")

// Sort: most dormant first.
results.sort { Map<String, Object> a, Map<String, Object> b ->
    (a.lastLoginMs as Long) <=> (b.lastLoginMs as Long)
}

// ── Render the report ─────────────────────────────────────────
def thStyle  = 'padding:10px 14px;text-align:left;background:#0052CC;color:#fff;font-size:13px;font-weight:600;white-space:nowrap;'
def tdStyle  = 'padding:9px 14px;font-size:13px;color:#172B4D;vertical-align:middle;'
def trOdd    = 'background:#FFFFFF;'
def trEven   = 'background:#F4F5F7;'
def tagStyle = 'display:inline-block;padding:2px 8px;border-radius:3px;font-size:11px;font-weight:600;margin:1px 2px;'

return OutputFormatter.markupBuilder {

    div(style: 'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;max-width:1100px;margin:20px auto;') {

        // ── Header ───────────────────────────────────────────
        div(style: 'background:#0052CC;border-radius:6px 6px 0 0;padding:20px 24px;') {
            h2(style: 'margin:0;color:#fff;font-size:20px;', 'Dormant User Report')
            p(style: 'margin:6px 0 0;color:#B3D4FF;font-size:13px;',
                "Generated: ${reportTime}  ·  Cutoff: ${cutoffDays} days  ·  Dormant users found: ${results.size()}")
        }

        if (results.isEmpty()) {
            div(style: 'padding:24px;background:#fff;border:1px solid #DFE1E6;border-top:none;border-radius:0 0 6px 6px;') {
                p(style: 'color:#6B778C;margin:0;', 'No dormant users found with the current settings.')
            }
            return
        }

        // ── Table ─────────────────────────────────────────────
        // One row per dormant user, sorted from most dormant to least.
        // "Login Count" — how many times they ever logged in total.
        // "Directory"   — Internal = deactivate in Jira.
        //                 LDAP/AD  = deactivate in your company directory.
        div(style: 'overflow-x:auto;border:1px solid #DFE1E6;border-top:none;border-radius:0 0 6px 6px;') {
            table(style: 'width:100%;border-collapse:collapse;') {
                thead {
                    tr {
                        ['Username', 'Display Name', 'Email', 'Last Login', 'Login Count', 'Directory', 'Licenced Groups'].each { col ->
                            th(style: thStyle, col)
                        }
                    }
                }
                tbody {
                    results.eachWithIndex { Map<String, Object> r, int i ->
                        tr(style: i % 2 == 0 ? trOdd : trEven) {
                            td(style: tdStyle) {
                                strong(OutputFormatter.escapeHtml(r.username as String))
                            }
                            td(style: tdStyle, OutputFormatter.escapeHtml(r.displayName as String))
                            td(style: tdStyle) {
                                a(href: "mailto:${OutputFormatter.escapeHtml(r.email as String)}",
                                  style: 'color:#0052CC;text-decoration:none;',
                                  OutputFormatter.escapeHtml(r.email as String))
                            }
                            td(style: tdStyle + (r.lastLogin == 'NEVER' ? 'color:#DE350B;font-weight:600;' : ''),
                                r.lastLogin as String)
                            td(style: tdStyle + 'text-align:center;', (r.loginCount as Long).toString())
                            td(style: tdStyle) {
                                def isExternal = !(r.directory as String).toLowerCase().contains('internal')
                                span(style: tagStyle + (isExternal ? 'background:#FFEBE6;color:#DE350B;' : 'background:#E3FCEF;color:#006644;'),
                                    OutputFormatter.escapeHtml(r.directory as String))
                            }
                            td(style: tdStyle, OutputFormatter.escapeHtml(r.licencedGroups as String))
                        }
                    }
                }
            }
        }
    }
}
