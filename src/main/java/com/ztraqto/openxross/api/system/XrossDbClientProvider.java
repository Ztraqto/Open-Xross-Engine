package com.ztraqto.openxross.api.system;

import com.ztraqto.openxross.XrossEngine;
import com.ztraqto.openxross.api.database.XrossDbClient;
import com.ztraqto.openxross.config.XrossConfiguration;

/**
 * System-provider contract for replacing the built-in XrossDB client.
 *
 * <p>A Cloudflare D1 integration can implement this interface and return an
 * {@link XrossDbClient} that talks to a Worker/D1 gateway without changing the
 * engine or application plugins.</p>
 */
public interface XrossDbClientProvider extends XrossSystemProvider {
    XrossDbClient createClient(XrossEngine engine, XrossConfiguration configuration) throws Exception;
}
