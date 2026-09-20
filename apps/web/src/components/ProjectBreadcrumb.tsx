import { Dropdown, type MenuProps } from 'antd'
import { ChevronDown, FolderKanban } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router'

import { getProject, listProjects, type DataProject } from '@/api/projects'
import { paths } from '@/app/paths'

export interface ProjectBreadcrumbProps {
  projectId: string
  projectName?: string | null
  currentProject?: DataProject | null
  switchTarget?: 'capture-groups' | 'members'
  children?: React.ReactNode
}

export function ProjectBreadcrumb({
  projectId,
  projectName: propProjectName,
  currentProject,
  switchTarget = 'capture-groups',
  children,
}: ProjectBreadcrumbProps) {
  const navigate = useNavigate()
  const [projectList, setProjectList] = useState<DataProject[]>([])
  const [loadingList, setLoadingList] = useState(false)
  const [fetchedName, setFetchedName] = useState<string | null>(null)

  const initialName = propProjectName ?? currentProject?.display_name ?? null

  useEffect(() => {
    if (initialName) return
    const request = new AbortController()
    void getProject(projectId, request.signal)
      .then((project) => {
        if (!request.signal.aborted) setFetchedName(project.display_name)
      })
      .catch(() => {
        /* The calling page retains its own access and error handling. */
      })
    return () => request.abort()
  }, [projectId, initialName])

  const displayName = initialName ?? fetchedName ?? projectId

  const loadProjectOptions = useCallback(async () => {
    if (loadingList) return
    setLoadingList(true)
    try {
      const items = await listProjects()
      setProjectList(items)
    } catch {
      // Retain existing list on network failure
    } finally {
      setLoadingList(false)
    }
  }, [loadingList])

  const projectMenuItems = useMemo((): MenuProps['items'] => {
    const list = projectList.length > 0 ? projectList : currentProject ? [currentProject] : []
    const items: MenuProps['items'] = list.map((item) => ({
      key: item.project_id,
      label: (
        <span className="project-switcher-item">
          <span>{item.display_name}</span>
          {item.project_id === projectId ? (
            <span className="project-switcher-active-tag">当前</span>
          ) : null}
        </span>
      ),
      disabled: item.project_id === projectId,
    }))
    items.push(
      { type: 'divider' },
      {
        key: '__all_projects__',
        icon: <FolderKanban aria-hidden="true" size={14} strokeWidth={1.8} />,
        label: '全部项目',
      },
    )
    return items
  }, [projectList, currentProject, projectId])

  const handleProjectSwitch: MenuProps['onClick'] = ({ key }) => {
    if (key === '__all_projects__') {
      void navigate(paths.projects)
    } else if (key !== projectId) {
      if (switchTarget === 'members') {
        void navigate(`/projects/${key}/members`)
      } else {
        void navigate(paths.captureGroups(key))
      }
    }
  }

  return (
    <nav className="breadcrumb" aria-label="面包屑导航">
      <Link to={paths.projects}>项目</Link>
      <span className="breadcrumb-separator" aria-hidden="true">
        /
      </span>
      <Dropdown
        menu={{
          items: projectMenuItems,
          onClick: handleProjectSwitch,
        }}
        trigger={['click']}
        onOpenChange={(open) => {
          if (open) void loadProjectOptions()
        }}
      >
        <button
          type="button"
          className="project-switcher-trigger"
          aria-label={`当前项目：${displayName}，点击切换项目`}
        >
          <FolderKanban aria-hidden="true" size={13} strokeWidth={1.8} />
          <span className="project-switcher-name" title={displayName}>
            {displayName}
          </span>
          <ChevronDown aria-hidden="true" size={12} strokeWidth={1.8} />
        </button>
      </Dropdown>
      {children ? (
        <>
          <span className="breadcrumb-separator" aria-hidden="true">
            /
          </span>
          {children}
        </>
      ) : null}
    </nav>
  )
}
