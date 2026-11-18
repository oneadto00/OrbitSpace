package com.orbitspace.security;

import com.orbitspace.kernel.SpaceRole;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireSpaceRole {
    SpaceRole value();
    /** Required for device routes, e.g. metadata:write or patch:write. */
    String deviceScope() default "";
}
