import {
  FileContextType,
  useStartWorkspaceMutation
} from '../../services/codefreak-api'
import './WorkspacePage.less'
import WorkspaceTabsWrapper from './WorkspaceTabsWrapper'
import { useEffect, useState } from 'react'
import { Col, Row } from 'antd'
import useWorkspace from '../../hooks/workspace/useWorkspace'
import { WorkspaceTab } from '../../services/workspace-tabs'
import { EditorWorkspaceTab } from './EditorTabPanel'

export interface WorkspacePageProps {
  type: FileContextType
  onBaseUrlChange: (newBaseUrl: string) => void
}

const WorkspacePage = ({ type, onBaseUrlChange }: WorkspacePageProps) => {
  const [leftTabs] = useState<WorkspaceTab[]>([
    new EditorWorkspaceTab('main.py')
  ])

  const { baseUrl, answerId } = useWorkspace()

  const [startWorkspace, { data, called }] = useStartWorkspaceMutation({
    variables: {
      context: {
        id: answerId,
        type
      }
    }
  })

  useEffect(() => {
    if (!data && !called) {
      startWorkspace()
    }
  })

  useEffect(() => {
    if (data && baseUrl.length === 0) {
      onBaseUrlChange(data.startWorkspace.baseUrl)
    }
  }, [data, baseUrl, onBaseUrlChange])

  return (
    <Row gutter={4} className="workspace-page">
      <Col span={24}>
        <WorkspaceTabsWrapper tabs={leftTabs} />
      </Col>
    </Row>
  )
}

export default WorkspacePage
